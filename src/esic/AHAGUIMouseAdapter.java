package esic;

/*
 * This class is built upon a modified version of a mouse adapter class which only exists in the git master for graph stream
 * so is back ported here, and then modified to meet our needs. Original CeCILL-C / LGPL license applies to this class only. 
 * source back ported from: https://github.com/graphstream/gs-core/blob/master/src/org/graphstream/ui/view/util/MouseOverMouseManager.java
 * 
 * Original license from class we modified:
 * This program is free software distributed under the terms of two licenses, the
 * CeCILL-C license that fits European law, and the GNU Lesser General Public
 * License. You can  use, modify and/ or redistribute the software under the terms
 * of the CeCILL-C license as circulated by CEA, CNRS and INRIA at the following
 * URL <http://www.cecill.info> or under the terms of the GNU LGPL as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * See graphstream website/repo or licenses in /deps/org.graphstream/ for full info
 * 
 * We (ESIC @ WSU) relinquish any copyright claims to this modified class and defer to the original copyrights/licenses
 */

public class AHAGUIMouseAdapter extends org.graphstream.ui.view.util.DefaultMouseManager 
{
	public AHAGUIMouseAdapter(final long delay, AHAGUI target) 
  {
      super();
      this.delay = delay;
      m_target=target;
  }
	public void mousePressed(java.awt.event.MouseEvent e) 
	{
		curElement = view.findNodeOrSpriteAt(e.getX(), e.getY());
		if (curElement != null) 
		{
			mouseButtonPressOnElement(curElement, e);
			m_target.startedHoveringOverElementOrClicked(curElement, false);
		} 
		else 
		{
			x1 = e.getX();
			y1 = e.getY();
			mouseButtonPress(e);
		}
	}

	public void mouseDragged(java.awt.event.MouseEvent e) 
	{
		if (curElement != null) { elementMoving(curElement, e); } 
		else 
		{
			int x2 = e.getX(), y2 = e.getY();
			org.graphstream.ui.geom.Point3 center=view.getCamera().getViewCenter();
			org.graphstream.ui.geom.Point3 pixels=view.getCamera().transformGuToPx(center.x, center.y, center.z);
			pixels.x+=(x1-x2);
			pixels.y+=(y1-y2);
			center=view.getCamera().transformPxToGu(pixels.x, pixels.y);
			view.getCamera().setViewCenter(center.x, center.y, center.z);
			x1=x2;
			y1=y2;
		}
	}

	public void mouseReleased(java.awt.event.MouseEvent e) 
	{
		if (curElement != null) 
		{
			mouseButtonReleaseOffElement(curElement, e);
			curElement = null;
		} 
		else 
		{
			float x2 = e.getX(), y2 = e.getY(), t;
			if (x1 > x2) 
			{
				t = x1;
				x1 = x2;
				x2 = t;
			}
			if (y1 > y2) 
			{
				t = y1;
				y1 = y2;
				y2 = t;
			}
			mouseButtonRelease(e, view.allNodesOrSpritesIn(x1, y1, x2, y2));
		}
	}
	
	private org.graphstream.ui.graphicGraph.GraphicElement hoveredElement;
  private long hoveredElementLastChanged;
  private java.util.concurrent.locks.ReentrantLock hoverLock = new java.util.concurrent.locks.ReentrantLock();
  private java.util.Timer hoverTimer = new java.util.Timer(true);
  private HoverTimerTask latestHoverTimerTask;
  private final long delay;
  private final AHAGUI m_target;
  
  public void mouseMoved(java.awt.event.MouseEvent event) 
  {
      try {
          hoverLock.lockInterruptibly();
          boolean stayedOnElement = false;
          org.graphstream.ui.graphicGraph.GraphicElement currentElement = view.findNodeOrSpriteAt(event.getX(), event.getY());
          if (hoveredElement != null) 
          {
              stayedOnElement = currentElement == null ? false : currentElement.equals(hoveredElement);
              if (!stayedOnElement ) 
              { 
              	m_target.stoppedHoveringOverElement(hoveredElement);
              	this.hoveredElement = null;
              }
          }
          if (!stayedOnElement && currentElement != null) 
          {
              if (delay <= 0) { m_target.startedHoveringOverElementOrClicked(curElement, true); } 
              else 
              {
                  hoveredElement = currentElement;
                  hoveredElementLastChanged = event.getWhen();
                  if (latestHoverTimerTask != null) { latestHoverTimerTask.cancel(); }
                  latestHoverTimerTask = new HoverTimerTask(hoveredElementLastChanged, hoveredElement);
                  hoverTimer.schedule(latestHoverTimerTask, delay);
              }
          }
      } 
      catch(InterruptedException iex) { /*NOP*/ } 
      finally { hoverLock.unlock(); }
  }
  private final class HoverTimerTask extends java.util.TimerTask 
  {
      private final long lastChanged;
      private final org.graphstream.ui.graphicGraph.GraphicElement element;
      public HoverTimerTask(long lastChanged, org.graphstream.ui.graphicGraph.GraphicElement element) 
      {
          this.lastChanged = lastChanged;
          this.element = element;
      }
      public void run() 
      {
          try 
          {
              hoverLock.lock();
              if (hoveredElementLastChanged == lastChanged) { m_target.startedHoveringOverElementOrClicked(element, true); }
          } 
          catch (Exception ex) { ex.printStackTrace(); } 
          finally { hoverLock.unlock(); }
      }
  }	
}
