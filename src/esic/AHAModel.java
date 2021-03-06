package esic;

//Copyright 2018 ESIC at WSU distributed under the MIT license. Please see LICENSE file for further info.

import org.graphstream.graph.Edge;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;
import org.graphstream.algorithm.measure.*;

public class AHAModel
{
	protected static class TableDataHolder
	{
		protected String[][] columnNames=null;
		protected Object[][][] tableData=null;
	}
	
	private static class ScoreItem
	{
		String criteria="", operation="";
		java.util.Vector<String> criteriaValues=null;
		int scoreDelta=0;
		public ScoreItem(String op, String crit, java.util.Vector<String> critvals, int sD)
		{
			operation=op;
			criteria=crit;
			criteriaValues=critvals;
			scoreDelta=sD;
		}
	}
	
	public static enum ScoreMethod {Normal,WorstCommonProc,ECScore}

	private java.util.ArrayList<ScoreItem> m_scoreTable=new java.util.ArrayList<ScoreItem>(32);
	protected boolean m_debug=false, m_verbose=false, m_multi=true, m_overlayCustomScoreFile=false, m_hideOSProcs=false; //flags for verbose output, hiding of operating system procs, and drawing of multiple edges between verticies
	protected String m_inputFileName="BinaryAnalysis.csv", m_scoreFileName="scorefile.csv";
	protected int m_minScoreLowVuln=25, m_minScoreMedVuln=15;
	protected java.util.TreeMap<String,String> m_allListeningProcessMap=new java.util.TreeMap<String,String>(), m_osProcs=new java.util.TreeMap<String,String>();
	protected java.util.TreeMap<String,String> m_intListeningProcessMap=new java.util.TreeMap<String,String>(), m_extListeningProcessMap=new java.util.TreeMap<String,String>();
	protected java.util.TreeMap<String,String> m_knownAliasesForLocalComputer=new java.util.TreeMap<String,String>(), m_miscMetrics=new java.util.TreeMap<String,String>();
	protected java.util.TreeMap<String,Integer> m_listeningPortConnectionCount=new java.util.TreeMap<String,Integer>();
	protected Graph m_graph=null;
	protected AHAGUI m_gui=null;
	
	protected static final String CUSTOMSTYLE="ui.weAppliedCustomStyle";
	protected String styleSheet = 	"graph { fill-color: black; }"+
			"node { size: 30px; fill-color: red; text-color: white; text-style: bold; text-size: 12; text-background-color: #222222; text-background-mode: plain; }"+
			"node.low { fill-color: green; }"+
			"node.high { fill-color: orange; }"+
			"node.medium { fill-color: yellow; }"+
			"node.custom { fill-mode: dyn-plain; }"+
			"node.external { size: 50px; fill-color: red; }"+
			"node:clicked { fill-color: blue; }"+
			"edge { shape: line; fill-color: #CCCCCC; }"+
			"edge.tw { stroke-mode: dashes; fill-color: #CCCCCC; }"+
			"edge.duplicate { fill-color: #303030; }"+
			"edge.duplicatetw { fill-color: #303030; stroke-mode: dashes; }"+
			"edge.external { fill-color: #883030; }"+
			"edge.externaltw { fill-color: #883030; stroke-mode: dashes; }"+
			"edge.duplicateExternal { fill-color: #553030; }"+
			"edge.duplicateExternaltw { fill-color: #553030; stroke-mode: dashes; }";

	public static String scrMethdAttr(ScoreMethod m) { 	return "ui.ScoreMethod"+m; }
	
	private void readScoreTable(String filename)
	{
		if (filename==null || filename.equals("")) { filename="MetricsTable.cfg"; }
		System.out.println("Reading MetricsTable file="+filename);
		java.io.BufferedReader buff=null;
		try 
		{
			buff = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(filename),"UTF8"));//new java.io.BufferedReader(new java.io.FileReader(filename));
			String line="";
			while ((line = buff.readLine()) != null)
			{
				line=line.trim().trim().trim();
				if (line.startsWith("//") || line.startsWith("#") || line.length()<5) { continue; }
				String[] tokens=fixCSVLine(line);
				if (tokens.length<3) { System.out.println("Malformed MetricsTable line:|"+line+"|"); continue;}
				int scoreDelta=-10000;
				try { scoreDelta=Integer.parseInt(tokens[3]); }
				catch (Exception e) { e.printStackTrace(); }
				
				if (scoreDelta > -101 && scoreDelta < 101) //valid range is only -100 to 100
				{
					String[] criteria=tokens[2].split("\\|");
					java.util.Vector<String> criteriaVec=new java.util.Vector<String>();
					for (String s : criteria) { criteriaVec.add(s.trim().trim().trim()); }
					System.out.println("MetricsTable read criterion: if "+tokens[1]+"="+criteriaVec.toString()+" score="+scoreDelta); //TODO fix
					m_scoreTable.add(new ScoreItem(tokens[0],tokens[1],criteriaVec,scoreDelta)); //TODO this should probably get cleaned up, as comments and/or anything after the first main 3 fields will consume memory even though we never use them.
				}
			}
		}
		catch(Exception e) { e.printStackTrace(); }
	}
	
	protected void exploreAndScore(Graph graph) //explores the node graph and assigns a scaryness score
	{
		long time=System.currentTimeMillis();
		java.util.TreeMap<String, Integer> lowestScoreForUserName=new java.util.TreeMap<String, Integer>();
		for (Node node : graph) //Stage 1 of scoring, either the entirety of a scoring algorithm, such as "Normal", or the first pass for multi stage algs
		{
			try
			{
				String nodeClass=node.getAttribute("ui.class"); 
				int score=generateNormalNodeScore(node);
				node.addAttribute(scrMethdAttr(ScoreMethod.Normal), Integer.toString(score)); //if we didn't have a custom score from another file, use our computed score
				
				//Begin WorstUserProc stage1 scoring
				String nodeUserName=node.getAttribute("username");
				if ( nodeUserName!=null )
				{
					Integer lowScore=lowestScoreForUserName.remove(nodeUserName);
					if (lowScore!=null && score>lowScore) { score=lowScore.intValue(); }
					lowestScoreForUserName.put(nodeUserName,score);
				} //End WorstUserProc stage1 scoring
				
				{ //Begin EC Method stage1 scoring
					if(nodeClass!=null && nodeClass.equalsIgnoreCase("external")) { node.addAttribute(scrMethdAttr(ScoreMethod.ECScore)+"Tmp", Integer.toString(200)); }
					else { node.addAttribute(scrMethdAttr(ScoreMethod.ECScore)+"Tmp", Double.toString(100-score)); } 
				} //End EC Method stage 1 scoring
			} catch (Exception e) { e.printStackTrace(); }
		}

		//Begin EC between loop compute
		EigenvectorCentrality ec = new EigenvectorCentrality(scrMethdAttr(ScoreMethod.ECScore)+"Tmp", org.graphstream.algorithm.measure.AbstractCentrality.NormalizationMode.MAX_1_MIN_0, 100, scrMethdAttr(ScoreMethod.Normal));
		ec.init(graph);
		ec.compute();
		if (m_verbose) { System.out.println("Worst User Scores="+lowestScoreForUserName); }
		//END EC between 
		
		for (Node node:graph) //Stage 2 of scoring, for scoring algorithms that need to make a second pass over the graph
		{
			try
			{ //Begin WorstUserProc stage2 scoring
				String nodeUserName=node.getAttribute("username");
				if (nodeUserName!=null)
				{
					Integer lowScore=lowestScoreForUserName.get(node.getAttribute("username"));
					if (lowScore==null) { System.err.println("no low score found, this should not happen"); continue; }
					node.addAttribute(scrMethdAttr(ScoreMethod.WorstCommonProc), lowScore.toString());
				} //End WorstUserProc stage2 scoring
				{ //Begin EC stage2 scoring
					double nodeScore   = Double.valueOf(node.getAttribute(scrMethdAttr(ScoreMethod.Normal)));
					double ecNodeScore = node.getAttribute(scrMethdAttr(ScoreMethod.ECScore)+"Tmp");
					ecNodeScore = nodeScore * (1-2*ecNodeScore);
					node.addAttribute(scrMethdAttr(ScoreMethod.ECScore), ((Integer)((Double)ecNodeScore).intValue()).toString());
				} //End EC stage2 scoring
			} catch (Exception e) { e.printStackTrace(); }
		}
		swapNodeStyles(ScoreMethod.Normal, time); //since we just finished doing the scores, swap to the 'Normal' score style when we're done.
	}

	protected int generateNormalNodeScore(Node n)
	{
		int score=0;
		String scoreReason="", extendedReason="";
		if (m_verbose) { System.out.println("Process: "+n.getId()+" examining metrics:"); }
		for (ScoreItem si : m_scoreTable) 
		{
			try
			{
				int numberOfTimesTrue=0;
				if(n.getAttribute(si.criteria) != null)
				{
					String attribute=n.getAttribute(si.criteria).toString().toLowerCase();
					for (String criterion : si.criteriaValues)
					{ //TODO add operation support here
						if( (si.operation.equals("eq") && attribute.equals(criterion)) || (si.operation.equals("ct") && attribute.contains(criterion))) 
						{ 
							score=score+si.scoreDelta;
							numberOfTimesTrue++;
						}
					}
					if (m_verbose) { System.out.println("    "+si.criteria+": input='"+attribute+"' looking for="+si.criteriaValues+" matched "+numberOfTimesTrue+" times"); }//TODO make printout more obvious here
					if (numberOfTimesTrue>0) { scoreReason+=", "+si.criteria+"="+(si.scoreDelta*numberOfTimesTrue); } //TODO this will need cleanup 
				}
				String xtra="";
				if (numberOfTimesTrue>1) { xtra=" x"+numberOfTimesTrue; }
				extendedReason+=", "+si.criteria+"."+si.operation+si.criteriaValues+":"+si.scoreDelta+"="+capitalizeFirstLetter(Boolean.toString(numberOfTimesTrue>0))+xtra;
			}
			catch (Exception e) { System.out.println(e.getMessage()); }
		}
		if (m_verbose) { System.out.println("    Score: " + score); }
		n.addAttribute(scrMethdAttr(ScoreMethod.Normal)+"Reason", "FinalScore="+score+scoreReason);
		n.addAttribute(scrMethdAttr(ScoreMethod.Normal)+"ExtendedReason", "FinalScore="+score+extendedReason);
		return score;
	}

	public void swapNodeStyles(ScoreMethod m, long startTime)
	{
		for (Node n : m_graph)
		{
			try
			{
				String currentClass=n.getAttribute("ui.class"), customStyle=n.getAttribute(CUSTOMSTYLE);
				String sScore=n.getAttribute(scrMethdAttr(m)), sScoreReason=n.getAttribute(scrMethdAttr(m)+"Reason"), sScoreExtendedReason=n.getAttribute(scrMethdAttr(m)+"ExtendedReason"); 
				Integer intScore=null;
				try {intScore=Integer.parseInt(sScore);}
				catch (Exception e) {} 
				if (currentClass==null || !currentClass.equalsIgnoreCase("external") || intScore!=null)
				{
					if (currentClass!=null && currentClass.equalsIgnoreCase("external"))
					{ 
						n.addAttribute("ui.score", "0");
						n.addAttribute("ui.scoreReason", "External Node");
						n.addAttribute("ui.scoreExtendedReason", "External Node");
					}
					else if (m_overlayCustomScoreFile==true && customStyle!=null && customStyle.equalsIgnoreCase("yes"))
					{
						String score=n.getAttribute(CUSTOMSTYLE+".score");
						String style=n.getAttribute(CUSTOMSTYLE+".style");
						n.removeAttribute("ui.class");
						n.addAttribute("ui.style", style);
						n.addAttribute("ui.score", score);
						n.addAttribute("ui.scoreReason", "Custom Scorefile Overlay");
						n.addAttribute("ui.scoreExtendedReason", "Custom Scorefile Overlay");
					}
					else if (intScore!=null)
					{ 
						int score=intScore.intValue();
						n.addAttribute("ui.score", score);
						if (sScoreReason!=null) { n.addAttribute("ui.scoreReason", sScoreReason); } //TODO: since scoreReason only really exists for 'normal' this means that 'normal' reason persists in other scoring modes. For modes that do not base their reasoning on 'normal' this is probably incorrect.
						if (sScoreExtendedReason!=null) { n.addAttribute("ui.scoreExtendedReason", sScoreExtendedReason); }
						String strScore="High";
						n.addAttribute("ui.class", "high"); //default
						if(score > m_minScoreLowVuln) { n.addAttribute("ui.class", "low"); strScore="Low"; }
						else if(score > m_minScoreMedVuln) { n.addAttribute("ui.class", "medium");  strScore="Medium";} 
						if (m_verbose) { System.out.println(n.getId()+" Applying Score: "+score+"   Vulnerability Score given: "+strScore); }
					}
				}
			}
			catch(Exception e) { e.printStackTrace(); }
		}
		System.out.println("Graph score complete using method="+m+" with useCustomScoring="+Boolean.toString(m_overlayCustomScoreFile)+". Took "+(System.currentTimeMillis()-startTime)+"ms.");
	}

	protected static String[] fixCSVLine(String s) //helper function to split, lower case, and clean lines of CSV into tokens
	{
		String[] ret=null;
		try
		{
			ret=s.replace('\ufeff', ' ').toLowerCase().split("\",\""); //the replace of ("\ufeff", "") removes the unicode encoding char at the beginning of the file, if it exists in the line. bleh.
			for (int i=0;i<ret.length;i++)
			{
				if (ret[i]!=null) { ret[i]=ret[i].replaceAll("\"", "").trim(); } //remove double quotes as we break into tokens, trim any whitespace on the outsides
			}
		}
		catch (Exception e) { System.out.print("fixCSVLine:"); e.printStackTrace(); }
		return ret;
	}
	
	public static String substringBeforeInstanceOf(String s, String separator)
	{
		int index=s.lastIndexOf(separator);
		if (index > 1) { s=s.substring(0, index); }
		return s;
	}
	
	private void bumpIntRefCount(java.util.TreeMap<String,Integer> dataset, String key, int ammountToBump)
	{
		Integer value=dataset.get(key);
		if ( value==null ) { value=Integer.valueOf(0); }
		value+=ammountToBump;
		dataset.put(key, value); //System.out.println("Bumping ref for key="+key+" to new value="+value);
	}

	protected void readInputFile()
	{
		System.out.println("Reading primary input file="+m_inputFileName);
		m_knownAliasesForLocalComputer.put("127.0.0.1", "127.0.0.1"); //ensure these obvious ones are inserted in case we never see them in the scan
		m_knownAliasesForLocalComputer.put("::1", "::1"); //ensure these obvious ones are inserted in case we never see them in the scan
		java.io.BufferedReader br = null;
		int lineNumber=0;
		java.util.TreeMap<String,Integer> hdr=new java.util.TreeMap<String,Integer>();
		try 
		{
			br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(m_inputFileName),"UTF8"));
			String line = "", header[]=fixCSVLine(br.readLine());
			for (int i=0;i<header.length;i++) 
			{ 
				String headerToken=header[i];
				if (headerToken.contains("processname") && !headerToken.equals("processname") && headerToken.length()=="processname".length() ) { System.out.println("Had to fix up 'processname' headertoken due to ByteOrderMark issues.");headerToken="processname"; }
				if (headerToken.contains("processname") && !headerToken.equals("processname") && Math.abs(headerToken.length()-"processname".length())<5) { System.out.println("Had to fix up 'processname' headertoken due to ByteOrderMark issues. hdrlen="+headerToken.length()+" ptlen="+"processname".length()); headerToken="processname"; }
				hdr.put(headerToken, Integer.valueOf(i)); 
			}
			String[] required={"processname","pid","protocol","state","localport","localaddress","remoteaddress","remoteport","remotehostname"};
			for (String s:required) { if (hdr.get(s)==null) { System.out.println("Input file: required column header field=\""+s+"\" was not detected, this will not go well."); } }

			while ((line = br.readLine()) != null) //this is the first loop, in this loop we're loading all the vertexes and their meta data, so we can then later connect the vertices
			{
				lineNumber++;
				try
				{
					String[] tokens = fixCSVLine(line); 
					if (tokens.length<5) { System.err.println("Skipping line #"+lineNumber+" because it is malformed."); continue; }
					String fromNode=tokens[hdr.get("processname")]+"_"+tokens[hdr.get("pid")], protoLocalPort=tokens[hdr.get("protocol")]+"_"+tokens[hdr.get("localport")];
					String connectionState=tokens[hdr.get("state")], localAddr=tokens[hdr.get("localaddress")].trim();
				
					Node node = m_graph.getNode(fromNode);
					if(node == null)
					{
						if (m_debug) { System.out.println("Found new process: Name=|"+fromNode+"|"); }
						node = m_graph.addNode(fromNode);
					}
					if (connectionState.contains("listen") )
					{
						m_knownAliasesForLocalComputer.put(localAddr, localAddr);
						m_allListeningProcessMap.put(protoLocalPort,fromNode); //push a map entry in the form of (proto_port, procname_PID) example map entry (tcp_49263, alarm.exe_5)
						if (m_debug) { System.out.printf("ListenMapPush: localPort=|%s| fromNode=|%s|\n",protoLocalPort,fromNode); }
						String portMapKey="ui.localListeningPorts";
						if( !localAddr.equals("127.0.0.1") && !localAddr.equals("::1") && !localAddr.startsWith("192.168") && !localAddr.startsWith("10.")) //TODO we really want anything that's not listening on localhost here: localAddr.equals("0.0.0.0") || localAddr.equals("::") || 
						{ 
							Edge e=m_graph.addEdge(node+"_external",node.toString(),"external");
							e.addAttribute("ui.class", "external");
							node.addAttribute("ui.hasExternalConnection", "yes");
							portMapKey="ui.extListeningPorts"; //since this is external, change the key we read/write when we store this new info
							m_extListeningProcessMap.put(protoLocalPort,fromNode);
						}
						else { m_intListeningProcessMap.put(protoLocalPort,fromNode); }
						java.util.TreeMap<String,String> listeningPorts=node.getAttribute(portMapKey);
						if (listeningPorts==null ) { listeningPorts=new java.util.TreeMap<String,String>(); }
						listeningPorts.put(protoLocalPort, protoLocalPort);
						node.addAttribute(portMapKey, listeningPorts);
					}
					for (int i=0;i<tokens.length && i<header.length;i++)
					{
						String processToken=tokens[i];
						if (  processToken==null || processToken.isEmpty() ) { processToken="null"; }
						if (m_debug) { System.out.printf("   Setting attribute %s for process %s\n",header[i],tokens[i]); }
						node.addAttribute(header[i],processToken);
					}
					if (lineNumber<5 && tokens[hdr.get("detectiontime")]!=null) { m_miscMetrics.put("detectiontime", tokens[hdr.get("detectiontime")]); } //only try to set the first few read lines
				}
				catch (Exception e) { System.out.print("start: first readthrough: input line "+lineNumber+":"); e.printStackTrace(); }
			}
		} 
		catch (Exception e) { System.out.print("start: first readthrough: input line "+lineNumber+":"); e.printStackTrace(); } 
		finally 
		{
				try { if (br!=null) br.close(); } 
				catch (Exception e) { System.out.print("Failed to close file: "); e.printStackTrace(); }
		}

		if (m_debug)
		{
			System.out.println("\nAll Listeners:");
			for (java.util.Map.Entry<String, String> entry : m_allListeningProcessMap.entrySet()) { System.out.println(entry.getKey()+"="+entry.getValue()); }
			System.out.println("-------\nInternal Listeners:");
			for (java.util.Map.Entry<String, String> entry : m_intListeningProcessMap.entrySet()) { System.out.println(entry.getKey()+"="+entry.getValue()); }
			System.out.println("-------\nExternal Listeners:");
			for (java.util.Map.Entry<String, String> entry : m_extListeningProcessMap.entrySet()) { System.out.println(entry.getKey()+"="+entry.getValue()); }
			System.out.println("-------\n");
		}
		m_knownAliasesForLocalComputer.remove("::");
		m_knownAliasesForLocalComputer.remove("0.0.0.0"); //TODO: these don't seem to make sense as aliases to local host, so i'm removing them for now

		lineNumber=0;
		br = null;
		try 
		{
			br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(m_inputFileName),"UTF8"));
			String line = "";
			br.readLine(); //consume first line
			while ((line = br.readLine()) != null)  //this is the second loop, in this loop we're loading the connections between nodes
			{
				lineNumber++;
				try
				{
					String[] tokens = fixCSVLine(line);
					if (tokens.length<5) { System.err.println("Skipping line #"+lineNumber+" because it is malformed."); continue; }
					String toNode, fromNode=tokens[hdr.get("processname")]+"_"+tokens[hdr.get("pid")], proto=tokens[hdr.get("protocol")], localPort=tokens[hdr.get("localport")], remotePort=tokens[hdr.get("remoteport")];
					String protoLocalPort=proto+"_"+localPort, protoRemotePort=proto+"_"+remotePort;
					String remoteAddr=tokens[hdr.get("remoteaddress")].trim(), localAddr=tokens[hdr.get("localaddress")], connectionState=tokens[hdr.get("state")], remoteHostname=tokens[hdr.get("remotehostname")];
					
					Node node = m_graph.getNode(fromNode);
					if(node == null)
					{
						System.out.println("WARNING: Second scan found new process: Name=|"+fromNode+"|, on line "+lineNumber+" ignoring."); 
						continue;
					}
					boolean duplicateEdge=false, timewait=false, exactSameEdgeAlreadyExists=false, isLocalOnly=false;
					Node tempNode=null;
					String connectionName=null,reverseConnectionName=null;
					if ( !connectionState.equalsIgnoreCase("listening") && !connectionState.equalsIgnoreCase("") ) //empty string is listening state for a bound udp socket on windows apparently
					{
						if (connectionState.toLowerCase().contains("wait") || connectionState.toLowerCase().contains("syn_") || connectionState.toLowerCase().contains("last_")) { timewait=true; }
						if ( m_knownAliasesForLocalComputer.get(remoteAddr)!=null ) 
						{ //if it's in that map, then this should be a connection to ourself
							isLocalOnly=true;
							if ( (toNode=m_allListeningProcessMap.get(protoRemotePort))!=null )
							{
								connectionName=(protoLocalPort+"<->"+protoRemotePort);
								reverseConnectionName=(protoRemotePort+"<->"+protoLocalPort);
							}
							else if ( m_knownAliasesForLocalComputer.get(localAddr)==null ) { System.out.printf("WARNING1: Failed to find listener for: %s External connection? info: line=%d name=%s local=%s:%s remote=%s:%s status=%s\n",protoRemotePort,lineNumber,fromNode,localAddr,localPort,remoteAddr,remotePort,connectionState);}
							else if (  m_knownAliasesForLocalComputer.get(localAddr)!=null && (m_allListeningProcessMap.get(protoLocalPort)!=null) ) { /*TODO: probably in this case we should store this line and re-examine it later after reversing the from/to and make sure someone else has the link?*/ /*System.out.printf("     Line=%d expected?: Failed to find listener for: %s External connection? info: name=%s local=%s:%s remote=%s:%s status=%s\n",lineNumber,protoRemotePort,fromNode,localAddr,localPort,remoteAddr,remotePort,connectionState);*/  }
							else if ( !connectionState.contains("syn-sent") ){ System.out.printf("WARNING3: Failed to find listener for: %s External connection? info: line=%d name=%s local=%s:%s remote=%s:%s status=%s\n",protoRemotePort,lineNumber,fromNode,localAddr,localPort,remoteAddr,remotePort,connectionState); }
						}
						else 
						{ 
							if (timewait && m_allListeningProcessMap.get(protoLocalPort)!=null) 
							{ 
								if (!fromNode.startsWith("unknown") && m_verbose) {System.out.println("Found a timewait we could correlate, but the name does not contain unknown. Would have changed from: "+fromNode+" to: "+m_allListeningProcessMap.get(protoLocalPort)+"?"); }
								else { fromNode=m_allListeningProcessMap.get(protoLocalPort); }
							}
							connectionName=(protoLocalPort+"<->"+protoRemotePort);
							reverseConnectionName=(protoRemotePort+"<->"+protoLocalPort);	
							toNode="Ext_"+remoteAddr;
							if (remoteHostname==null || remoteHostname.equals("")) { remoteHostname=remoteAddr; } //cover the case that there is no FQDN
						}
						
						if (connectionName!=null) { tempNode=m_graph.getNode(fromNode); } //some of the cases above wont actually result in a sane node setup, so only grab a node if we have connection name
						if (tempNode!=null)
						{
							for (Edge e : tempNode.getEdgeSet())
							{
								if (e.getOpposite(tempNode).getId().equals(toNode)) { duplicateEdge=true; }
								if (e.getId().equals(connectionName) || e.getId().equals(reverseConnectionName) ) { exactSameEdgeAlreadyExists=true; System.out.println("Exact same edge already exists!"); }
							}
							if (!exactSameEdgeAlreadyExists)
							{
								Edge e=m_graph.addEdge(connectionName,fromNode,toNode);
								if (m_debug) { System.out.println("Adding edge from="+fromNode+" to="+toNode); }
								if (m_allListeningProcessMap.get(protoLocalPort)!=null) { bumpIntRefCount(m_listeningPortConnectionCount,protoLocalPort,1); }
								if (e!=null && isLocalOnly)
								{
									e.addAttribute("layout.weight", 10); //try to make internal edges longer
									if (duplicateEdge) { e.addAttribute("layout.weight", 5); }
									if (timewait && !duplicateEdge) { e.addAttribute("ui.class", "tw"); }
									if (!timewait && duplicateEdge) { e.addAttribute("ui.class", "duplicate"); }
									if (timewait && duplicateEdge) { e.addAttribute("ui.class", "duplicatetw"); }
									if (m_allListeningProcessMap.get(protoRemotePort)!=null) { bumpIntRefCount(m_listeningPortConnectionCount,protoRemotePort,1); }
								}
								else if(e!=null)
								{
									m_graph.getNode(toNode).addAttribute("ui.class", "external");
									m_graph.getNode(toNode).addAttribute("hostname", remoteHostname);
									m_graph.getNode(toNode).addAttribute("IP", remoteAddr);
									e.addAttribute("layout.weight", 9); //try to make internal edges longer
									if (duplicateEdge) { e.addAttribute("layout.weight", 4); }
									if (!timewait && !duplicateEdge) { e.addAttribute("ui.class", "external"); }
									if (!timewait && duplicateEdge) { e.addAttribute("ui.class", "duplicateExternal"); }
									if (timewait && !duplicateEdge) { e.addAttribute("ui.class", "externaltw"); } 
									if (timewait && duplicateEdge) { e.addAttribute("ui.class", "duplicateExternaltw"); }
								}
							}
						}
					}
				}
				catch (Exception e) { System.out.print("start: second readthrough: input line "+lineNumber+":"); e.printStackTrace(); }
			}
		} 
		catch (Exception e) { System.out.print("start: second readthrough: input line "+lineNumber+":"); e.printStackTrace(); } 
		finally 
		{ if (br != null) 
			{ try { br.close(); } 
				catch (Exception e) { System.out.print("Failed to close file: "); e.printStackTrace(); }
			}
		}
		if (m_verbose) { System.out.println("All known aliases for the local machine address:"+m_knownAliasesForLocalComputer.keySet()); }
	}
	
	public static String getCommaSepKeysFromStringMap(java.util.Map<String, String> map)
	{
		StringBuilder sb=new StringBuilder("");
		if (map==null || map.isEmpty()) { return "None"; } //right now this makes for optimal code on the clients of this function, may not be the case in the future. 
		java.util.Iterator<String> it=map.keySet().iterator();
		while (it.hasNext())
		{
			sb.append(it.next());
			if (it.hasNext()) { sb.append(", "); }
		}
		return sb.toString();
	}

	protected void readCustomScorefile()
	{
		System.out.println("Using CustomScoreOverlay file="+m_scoreFileName);
		java.io.BufferedReader br = null;
		try 
		{
			br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(m_scoreFileName),"UTF8"));
			String line = "";
			int lineNumber=0;
			while ((line = br.readLine()) != null)  //this is the second loop, in this loop we're loading the connections between nodes
			{
				try
				{
					String[] tokens = fixCSVLine(line);
					String directive=tokens[0], style=tokens[2], nodePathName=tokens[3];
					String score=tokens[1].split("=")[1];

					lineNumber++; //we will now be on line 1.
					for (Node node:m_graph)
					{
						try
						{
							String processPath=node.getAttribute("processpath");
							if (processPath!=null && processPath.equalsIgnoreCase(nodePathName))
							{
								if(directive.equals("node") && nodePathName!=null)
								{
									node.addAttribute(CUSTOMSTYLE,"yes");
									node.addAttribute(CUSTOMSTYLE+".score", score);
									node.addAttribute(CUSTOMSTYLE+".style", style);
									System.out.printf("CustomScoreOverlay read line: node=%s path=%s, setting score=%s color=%s\n", node.getId(),nodePathName,score, style);
								}
								else if (directive.equals("edge") && nodePathName!=null)
								{
									String toName=tokens[4];
									for (Edge e : node.getEdgeSet())
									{
										Node toNode=e.getOpposite(node);
										String toNodeProcessPath=toNode.getAttribute("processpath");
										if ( toNodeProcessPath.equalsIgnoreCase(toName) )
										{
											e.addAttribute(CUSTOMSTYLE,"yes");
											e.addAttribute(CUSTOMSTYLE+".score", score);
											e.addAttribute(CUSTOMSTYLE+".style", style);
											System.out.printf("scorefile: found edge from=%s to=%s score=%s\n", nodePathName,toName,score);
										}
									}
								}
							}
						} catch (Exception e) { e.printStackTrace(); }
					}
					lineNumber++;
				} catch (Exception e)
				{
					System.out.println("Failed to parse line="+lineNumber);
					e.printStackTrace();
				}
			}
		} 
		catch (java.io.FileNotFoundException fne) { System.out.println("No scorefile.csv found."); }
		catch (Exception e) { e.printStackTrace(); } 
		finally 
		{
			if (br != null) 
			{
				try { br.close(); } 
				catch (Exception e) { e.printStackTrace(); }
			}
		}
	}
	
	protected void hideFalseExternalNode(Graph g, boolean hide) 
	{
		Node node=m_graph.getNode("external");
		try
		{
			if (m_verbose) { System.out.println("Hide/unhide node="+node.getId()); }
			if (hide) { node.addAttribute( "ui.hide" ); }
			else { node.removeAttribute( "ui.hide" ); }

			for (Edge e : node.getEdgeSet())
			{
				if (hide) { e.addAttribute( "ui.hide" ); }
				else { if (e.getOpposite(node).getAttribute("ui.hide")==null ) { e.removeAttribute( "ui.hide" ); } }
			}
		} catch (Exception e) { e.printStackTrace(); }
	}
	
	protected void useFQDNLabels(Graph g, boolean useFQDN) 
	{
		for (Node n : m_graph) { n.addAttribute("ui.label", capitalizeFirstLetter(n.getId())); } //add labels
		if (useFQDN) 
		{  
			for (Node n : m_graph) 
			{ 
				String temp=n.getAttribute("ui.class");
				if (temp!=null && temp.equals("external"))
				{
					if (!n.getId().equals("external")) { n.addAttribute("ui.label", capitalizeFirstLetter("Ext_"+n.getAttribute("hostname"))); }
				}
			}
		} 
	}
	
	protected void hideOSProcs(Graph g, boolean hide) 
	{
		m_hideOSProcs=hide;
		for (Node node:g)
		{
			try
			{
				String processPath=node.getAttribute("processpath");
				if (processPath!=null && m_osProcs.get(processPath)!=null)
				{
					if (m_verbose) { System.out.println("Hide/unhide node="+node.getId()); }
					if (m_hideOSProcs) { node.addAttribute( "ui.hide" ); }
					else { node.removeAttribute( "ui.hide" ); }

					for (Edge e : node.getEdgeSet())
					{
						if (m_hideOSProcs) { e.addAttribute( "ui.hide" ); }
						else { if (e.getOpposite(node).getAttribute("ui.hide")==null ) { e.removeAttribute( "ui.hide" ); } }
					}
				}
			} catch (Exception e) { e.printStackTrace(); }
		}
	}
	
	protected static String capitalizeFirstLetter(String s)
	{
		s = s.toLowerCase();
		return Character.toString(s.charAt(0)).toUpperCase()+s.substring(1);
	}
	
	protected void start()
	{
		m_graph.addAttribute("ui.stylesheet", styleSheet);
		m_graph.setAutoCreate(true);
		m_graph.setStrict(false);
		Node ext=m_graph.addNode("external");
		ext.addAttribute("ui.class", "external"); //Add a node for "external"
		ext.addAttribute("processpath","external"); //add fake process path
		
		System.out.println("JRE: Vendor="+System.getProperty("java.vendor")+", Version="+System.getProperty("java.version"));
		System.out.println("OS: Arch="+System.getProperty("os.arch")+" Name="+System.getProperty("os.name")+" Vers="+System.getProperty("os.version"));
		System.out.println("AHA-GUI Version: "+AHAModel.class.getPackage().getImplementationVersion()+" starting up.");
		
		readInputFile();
		readScoreTable(null);
		readCustomScorefile();
		useFQDNLabels(m_graph, m_gui.m_showFQDN.isSelected());
		exploreAndScore(m_graph);
		new Thread(){ //Don't do this until after we explore and score, to reduce odd concurrency errors
			public void run()
			{
				while (!Thread.interrupted())
				{
					try { m_gui.m_graphViewPump.blockingPump(); }
					catch (Exception e) { e.printStackTrace(); }
				}
			}
		}.start();
		
		try { Thread.sleep(1500); } catch (Exception e) {}
		m_gui.m_viewer.disableAutoLayout();
		try { Thread.sleep(100); } catch (Exception e) {} //add delay to see if issues with moving ext nodes goes away
		
		java.util.Vector<Node> leftSideNodes=new java.util.Vector<Node>(); //moved this below the 1.5s graph stabilization threshold to see if it makes odd occasional issues with moving ext nodes better
		for (Node n : m_graph) 
		{
			if (n.getId().contains("Ext_")) { leftSideNodes.add(n); }
			n.addAttribute("layout.weight", 6); //switched to add attribute rather than set attribute since it seems to prevent a possible race condition.
		}
		int numLeftNodes=leftSideNodes.size()+2; //1 is for main External node, 2 is so we dont put one at the very top or bottom
		leftSideNodes.insertElementAt(m_graph.getNode("external"),leftSideNodes.size()/2);
		
		int i=1;
		for (Node n : leftSideNodes)
		{ 
			org.graphstream.ui.geom.Point3 loc=m_gui.m_viewPanel.getCamera().transformPxToGu(30, (m_gui.m_viewPanel.getHeight()/numLeftNodes)*i);
			n.addAttribute("xyz", loc.x,loc.y,loc.z);
			i++;
		}
		writeReport(generateReport(),"AHA-GUI-Report.csv");
	}
	
	public static Object strAsInt(String s) //try to box in an integer (important for making sorters in tables work) but fall back to just passing through the Object
	{ 
		try { if (s!=null ) { return Integer.valueOf(s); } }
		catch (NumberFormatException nfe) {} //we really don't care about this, we'll just return the original object
		catch (Exception e) { System.out.println("s="+s);e.printStackTrace(); } //something else weird happened, let's print about it
		return s;
	}
	
	private TableDataHolder synch_report=new TableDataHolder(); //do not access from anywhere other than generateReport!
	protected TableDataHolder generateReport()
	{
		synchronized (synch_report)
		{
			if (synch_report.columnNames==null)
			{
				int NUM_SCORE_TABLES=2, tableNumber=0;
				String[][] columnHeaders= {{"Scan Information", "Value"},{"Process", "PID", "User","Connections","ExtPorts","Signed","ASLR","DEP","CFG","HiVA", "Score", "ECScore", "WPScore"},{}};
				Object[][][] tableData=new Object[NUM_SCORE_TABLES][][];
				{ //general info
					Object[][] data=new Object[8][2];
					int i=0;
					
					data[i][0]="Local Addresses of Scanned Machine";
					data[i++][1]=m_knownAliasesForLocalComputer.keySet().toString();
					data[i][0]="Local Time of Host Scan";
					data[i++][1]=m_miscMetrics.get("detectiontime");
					
					{
						int numExt=0, worstScore=100;
						double denominatorAccumulator=0.0d;
						String worstScoreName="";
						for (Node n : m_graph)
						{
							if (n.getAttribute("username")!=null && n.getAttribute("ui.hasExternalConnection")!=null && n.getAttribute("aslr")!=null && !n.getAttribute("aslr").equals("") && !n.getAttribute("aslr").equals("scanerror")) 
							{ 
								numExt++;
								String normalScore=n.getAttribute(AHAModel.scrMethdAttr(ScoreMethod.Normal));
								try
								{
									Integer temp=Integer.parseInt(normalScore);
									if (worstScore > temp) { worstScore=temp; worstScoreName=n.getId();}
									denominatorAccumulator+=(1.0d/(double)temp);
								} catch (Exception e) {}
							}
						}
						data[i][0]="Number of Externally Acccessible Processes";
						data[i++][1]=Integer.toString(numExt);
						data[i][0]="Score of Worst Externally Accessible Scannable Process";
						data[i++][1]="Process: "+worstScoreName+"  Score: "+worstScore;
						data[i][0]="Harmonic Mean of Scores of all Externally Accessible Processes";
						String harmonicMean="Harmonic Mean Computation Error";
						if (denominatorAccumulator > 0.000001d) { harmonicMean=String.format("%.2f", ((double)numExt)/denominatorAccumulator); }
						data[i++][1]=harmonicMean;
					}
					
					{
						int numInt=0, worstScore=100;
						double denominatorAccumulator=0.0d;
						String worstScoreName="";
						for (Node n : m_graph)
						{
							if (n.getAttribute("username")!=null && n.getAttribute("ui.hasExternalConnection")==null && n.getAttribute("aslr")!=null && !n.getAttribute("aslr").equals("") && !n.getAttribute("aslr").equals("scanerror")) 
							{ 
								numInt++;
								String normalScore=n.getAttribute(AHAModel.scrMethdAttr(ScoreMethod.Normal));
								try
								{
									Integer temp=Integer.parseInt(normalScore);
									if (worstScore > temp) { worstScore=temp; worstScoreName=n.getId();}
									denominatorAccumulator+=(1.0d/(double)temp);
								} catch (Exception e) {}
							}
						}
						data[i][0]="Number of Internally Acccessible Processes";
						data[i++][1]=Integer.toString(numInt);
						data[i][0]="Score of Worst Internally Accessible Scannable Process";
						data[i++][1]="Process: "+worstScoreName+"  Score: "+worstScore;
						data[i][0]="Harmonic Mean of Scores of all Internally Accessible Processes";
						String harmonicMean="Harominc Mean Computation Error";
						if (denominatorAccumulator > 0.000001d) { harmonicMean=String.format("%.2f", ((double)numInt)/denominatorAccumulator); }
						data[i++][1]=harmonicMean;
					}
					tableData[tableNumber]=data;
				}
				tableNumber++;
				{ //general node info
					tableData[tableNumber]=new Object[m_graph.getNodeCount()][];
					int i=0;
					for (Node n : m_graph)
					{
						int j=0; //tired of reordering numbers after moving columns around
						Object[] data=new Object[columnHeaders[tableNumber].length]; //if we run out of space we forgot to make a column header anyway
						String name=n.getAttribute("processname");
						if (name==null) { name=n.getAttribute("ui.label"); }
						data[j++]=name;
						data[j++]=strAsInt(n.getAttribute("pid"));
						data[j++]=n.getAttribute("username");
						data[j++]=Integer.valueOf(n.getEdgeSet().size()); //cant use wrapInt here because  //TODO: deduplicate connection set?
						String extPortString="";
						java.util.TreeMap<String,String> extPorts=n.getAttribute("ui.extListeningPorts");
						if (extPorts!=null) 
						{ 
							for (String s : extPorts.keySet())
							{
								String[] tmp=s.split("_");
								if (!extPortString.equals("")) { extPortString+=", "; }
								extPortString+=tmp[1];
							}
						}
						data[j++]=extPortString;
						data[j++]=n.getAttribute("authenticode");
						data[j++]=n.getAttribute("aslr"); //these all have to be lowercase to work remember :)
						data[j++]=n.getAttribute("dep");
						data[j++]=n.getAttribute("controlflowguard");
						data[j++]=n.getAttribute("highentropyva");
						data[j++]=strAsInt(n.getAttribute(AHAModel.scrMethdAttr(ScoreMethod.Normal)));
						data[j++]=strAsInt(n.getAttribute(AHAModel.scrMethdAttr(ScoreMethod.ECScore)));
						data[j++]=strAsInt(n.getAttribute(AHAModel.scrMethdAttr(ScoreMethod.WorstCommonProc)));
						tableData[tableNumber][i++]=data;
					}
				}
				synch_report.columnNames=columnHeaders;
				synch_report.tableData=tableData;
			}
			return synch_report;
		}
	}
	
	private void writeReport(TableDataHolder report, String fileName)
	{
		try (java.io.FileWriter fileOutput=new java.io.FileWriter(fileName))
		{
			for (int table=0;table<report.tableData.length;table++)
			{
				for (int tableHeaderCell=0;tableHeaderCell<report.columnNames[table].length;tableHeaderCell++) //print column headers
				{
					fileOutput.write("\""+report.columnNames[table][tableHeaderCell]+"\",");
				}
				fileOutput.write("\n");
				for (int row=0; row<report.tableData[table].length;row++) //print table data
				{
					for (int cell=0;cell<report.tableData[table][row].length;cell++)
					{
						fileOutput.write("\""+report.tableData[table][row][cell]+"\",");
					}
					fileOutput.write("\n"); //end table line
				}
				fileOutput.write("\n\n\n"); //3 lines between tables
			}
		} 
		catch (Exception e) { e.printStackTrace(); }
	}
	
	public AHAModel(String args[])
	{
		{ //fill m_osProcs
			m_osProcs.put("c:\\windows\\system32\\services.exe","services.exe");
			m_osProcs.put("c:\\windows\\system32\\svchost.exe","svchost.exe");
			m_osProcs.put("c:\\windows\\system32\\wininit.exe","wininit.exe");
			m_osProcs.put("c:\\windows\\system32\\lsass.exe","lsass.exe");
			m_osProcs.put("null","unknown");
			m_osProcs.put("system","system");
		}
		
		boolean bigfont=false;
		for (String s : args)
		{
			try
			{
				String[] argTokens=s.split("=");
				if (argTokens[0]==null) { continue; }
				if (argTokens[0].equalsIgnoreCase("--debug")) { m_debug=true; } //print more debugs while running
				if (argTokens[0].equalsIgnoreCase("--verbose")) { m_verbose=true; } //print more debugs while running
				if (argTokens[0].equalsIgnoreCase("--single")) { m_multi=false; } //draw single lines between nodes
				if (argTokens[0].equalsIgnoreCase("--bigfont")) { bigfont=true; } //use 18pt font instead of default
				if (argTokens[0].equalsIgnoreCase("scorefile")) { m_scoreFileName=argTokens[1]; m_overlayCustomScoreFile=true; } //path to custom score file, and enable since...that makes sense in this case
				if (argTokens[0].equalsIgnoreCase("inputFile")) { m_inputFileName=argTokens[1]; } //path to input file
				if (argTokens[0].equalsIgnoreCase("lowVulnThreshold")) { m_minScoreLowVuln=Integer.parseInt(argTokens[1]); } //set the minimum score to attain low vulnerability status
				if (argTokens[0].equalsIgnoreCase("medVulnThreshold")) { m_minScoreMedVuln=Integer.parseInt(argTokens[1]); } //set the minimum score to attain medium vulnerability status
				
				if (argTokens[0].equals("help")||argTokens[0].equals("?")) 
				{  
					System.out.println
					(
							"Arguments are as follows:"+
							"--debug : print additional information to console while running"+
							"--single : use single lines between nodes with multiple connections "+
							"--bigfont : use 18pt font instead of the default 12pt font (good for demos) "+
							"scorefile=scorefile.csv : use the scorefile specified after the equals sign "+
							"inputFile=inputFile.csv : use the inputFile specified after the equals sign "+
							"lowVulnThreshold=25 : use the integer after the equals as the minimum node score to get a low vulnerability score (green) "+
							"medVulnThreshold=15 : use the integer after the equals as the minimum node score to get a medium vulnerability score (yellow) "
					); return;
				}
			} catch (Exception e) { e.printStackTrace(); }
		}
		java.awt.Font uiFont=new java.awt.Font(java.awt.Font.MONOSPACED,java.awt.Font.PLAIN,12);
		if (bigfont) { uiFont=new java.awt.Font(java.awt.Font.MONOSPACED,java.awt.Font.PLAIN,18); }
		AHAGUIHelpers.applyTheme(uiFont);
		m_gui =new AHAGUI(this);
	}
}
