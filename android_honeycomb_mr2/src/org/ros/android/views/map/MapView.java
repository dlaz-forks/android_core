/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.ros.android.views.map;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import org.ros.message.MessageListener;
import org.ros.message.geometry_msgs.PoseStamped;
import org.ros.message.geometry_msgs.PoseWithCovarianceStamped;
import org.ros.message.nav_msgs.OccupancyGrid;
import org.ros.message.nav_msgs.Path;
import org.ros.node.Node;
import org.ros.node.NodeMain;
import org.ros.node.topic.Publisher;

import java.util.Calendar;

/**
 * Displays a map and other data on a OpenGL surface. This is an interactive map
 * that allows the user to pan, zoom, specify goals, initial pose, and regions.
 * 
 * @author munjaldesai@google.com (Munjal Desai)
 */
public class MapView extends GLSurfaceView implements NodeMain, OnTouchListener {

  /**
   * Topic name for the map.
   */
  private static final String MAP_TOPIC_NAME = "~map";
  /**
   * Topic name at which the initial pose will be published.
   */
  private static final String INITIAL_POSE_TOPIC_NAME = "~initialpose";
  /**
   * Topic name at which the goal message will be published.
   */
  private static final String SIMPLE_GOAL_TOPIC = "simple_waypoints_server/goal_pose";
  /**
   * Topic name for the subscribed AMCL pose.
   */
  private static final String ROBOT_POSE_TOPIC = "~pose";
  /**
   * Topic name for the subscribed path.
   */
  private static final String PATH_TOPIC = "~global_plan";
  /**
   * Time in milliseconds after which taps are not considered to be double taps.
   */
  private static final int DOUBLE_TAP_TIME = 200;
  /**
   * Time in milliseconds for which the user must keep the contact down without
   * moving to trigger a press and hold gesture.
   */
  private static final int PRESS_AND_HOLD_TIME = 600;
  /**
   * Threshold to indicate that the map should be moved. If the user taps and
   * moves at least that distance, we enter the map move mode.
   */
  private static final int MOVE_MAP_DISTANCE_THRESHOLD = 10;
  /**
   * The OpenGL renderer that creates and manages the surface.
   */
  private MapRenderer mapRenderer;
  private InteractionMode currentInteractionMode = InteractionMode.INVALID;
  /**
   * A sub-mode of InteractionMode.SPECIFY_LOCATION. True when the user is
   * trying to set the initial pose of the robot. False when the user is
   * specifying the goal point for the robot to autonomously navigate to.
   */
  private boolean initialPoseMode;
  /**
   * Time in milliseconds when the last contact down occurred. Used to determine
   * a double tap event.
   */
  private long previousContactDownTime;
  /**
   * Records the on-screen location (in pixels) of the contact down event. Later
   * when it is determined that the user was specifying a destination this
   * points is translated to a position in the real world.
   */
  private Point goalContact = new Point();
  /**
   * Keeps the latest coordinates of up to 2 contacts.
   */
  private Point[] previousContact = new Point[2];
  /**
   * Used to determine a long press and hold event in conjunction with
   * {@link #longPressRunnable}.
   */
  private Handler longPressHandler = new Handler();
  /**
   * Publisher for the initial pose of the robot for AMCL.
   */
  private Publisher<PoseWithCovarianceStamped> initialPose;
  /**
   * Publisher for user specified goal for autonomous navigation.
   */
  private Publisher<PoseStamped> goalPublisher;
  private String poseFrameId;
  private Node node;

  public final Runnable longPressRunnable = new Runnable() {
    @Override
    public void run() {
      // TODO: Draw a state diagram and check what states can transition here.
      // This might help with the somewhat scattered removeCallbacks.
      longPressHandler.removeCallbacks(longPressRunnable);
      // The user is trying to specify a location to the robot.
      if (currentInteractionMode == InteractionMode.INVALID) {
        currentInteractionMode = InteractionMode.SPECIFY_LOCATION;
        // Show the goal icon.
        mapRenderer.userGoalVisible(true);
        // Move the goal icon to the correct location in the map.
        mapRenderer.updateUserGoal(mapRenderer.toOpenGLPose(goalContact, 0));
        requestRender();
      }
    }
  };

  public MapView(Context context) {
    super(context);
    mapRenderer = new MapRenderer();
    setEGLConfigChooser(8, 8, 8, 8, 0, 0);
    getHolder().setFormat(PixelFormat.TRANSLUCENT);
    setZOrderOnTop(true);
    setRenderer(mapRenderer);
    // This is important since the display needs to be updated only when new
    // data is received.
    setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    previousContact[0] = new Point();
    previousContact[1] = new Point();
  }

  @Override
  public void onStart(Node node) {
    this.node = node;
    // Initialize the goal publisher.
    goalPublisher = node.newPublisher(SIMPLE_GOAL_TOPIC, "geometry_msgs/PoseStamped");
    // Initialize the initial pose publisher.
    initialPose =
        node.newPublisher(INITIAL_POSE_TOPIC_NAME, "geometry_msgs/PoseWithCovarianceStamped");
    // Subscribe to the map.
    node.newSubscriber(MAP_TOPIC_NAME, "nav_msgs/OccupancyGrid",
        new MessageListener<OccupancyGrid>() {
          @Override
          public void onNewMessage(final OccupancyGrid map) {
            post(new Runnable() {
              @Override
              public void run() {
                // Show the map.
                mapRenderer.updateMap(map);
                requestRender();
              }
            });
          }
        });
    // Subscribe to the pose.
    node.newSubscriber(ROBOT_POSE_TOPIC, "geometry_msgs/PoseStamped",
        new MessageListener<PoseStamped>() {
          @Override
          public void onNewMessage(final PoseStamped message) {
            post(new Runnable() {
              @Override
              public void run() {
                poseFrameId = message.header.frame_id;
                // Update the robot's location on the map.
                mapRenderer.updateRobotPose(message.pose);
                requestRender();
              }
            });
          }
        });
    // Subscribe to the current goal.
    node.newSubscriber(SIMPLE_GOAL_TOPIC, "geometry_msgs/PoseStamped",
        new MessageListener<PoseStamped>() {
          @Override
          public void onNewMessage(final PoseStamped goal) {
            post(new Runnable() {
              @Override
              public void run() {
                // Update the location of the current goal on the map.
                mapRenderer.updateCurrentGoalPose(goal.pose);
                requestRender();
              }
            });
          }
        });
    // Subscribe to the current path plan.
    node.newSubscriber(PATH_TOPIC, "nav_msgs/Path", new MessageListener<Path>() {
      @Override
      public void onNewMessage(final Path path) {
        post(new Runnable() {
          @Override
          public void run() {
            // Update the path on the map.
            mapRenderer.updatePath(path);
          }
        });
      }
    });
    // Start listening for touch events.
    setOnTouchListener(this);
  }

  @Override
  public void onShutdown(Node node) {
  }

  @Override
  public boolean onTouch(View v, MotionEvent event) {
    final int action = event.getAction();
    switch (action & MotionEvent.ACTION_MASK) {
      case MotionEvent.ACTION_MOVE: {
        contactMove(event);
        break;
      }
      case MotionEvent.ACTION_DOWN: {
        return contactDown(event);
      }
      case MotionEvent.ACTION_POINTER_1_DOWN: {
        // If the user is trying to specify a location on the map.
        if (currentInteractionMode == InteractionMode.SPECIFY_LOCATION) {
          // Cancel the currently specified location and reset the interaction
          // state machine.
          resetInteractionState();
        }
        previousContact[1].x = (int) event.getX(event.getActionIndex());
        previousContact[1].y = (int) event.getY(event.getActionIndex());
        break;
      }
      case MotionEvent.ACTION_POINTER_2_DOWN: {
        // If there is a third contact on the screen then reset the interaction
        // state machine.
        resetInteractionState();
        break;
      }
      case MotionEvent.ACTION_UP: {
        contactUp(event);
        break;
      }
    }
    return true;
  }

  /**
   * Sets the map in robot centric or map centric mode. In robot centric mode
   * the robot is always facing up and the map move and rotates to accommodate
   * that. In map centric mode the map does not rotate. The robot can be
   * centered if the user double taps on the view.
   * 
   * @param isRobotCentricMode
   *          True for robot centric mode and false for map centric mode.
   */
  public void setViewMode(boolean isRobotCentricMode) {
    mapRenderer.setViewMode(isRobotCentricMode);
  }

  /**
   * Enable the initial pose selection mode. Next time the user specifies a pose
   * it will be published as {@link #initialPose}. This mode is automatically
   * disabled once an initial pose has been specified or if a user cancels the
   * pose selection gesture (second finger on the screen).
   */
  public void initialPose() {
    initialPoseMode = true;
  }

  private void contactMove(MotionEvent event) {
    // If only one contact is on the view.
    if (event.getPointerCount() == 1) {
      // And the user is moving the map.
      if (currentInteractionMode == InteractionMode.INVALID) {
        if (calcDistance(event.getX(), event.getY(), previousContact[0].x, previousContact[0].y) > MOVE_MAP_DISTANCE_THRESHOLD) {
          currentInteractionMode = InteractionMode.MOVE_MAP;
          longPressHandler.removeCallbacks(longPressRunnable);
          return;
        }
      }
      if (currentInteractionMode == InteractionMode.MOVE_MAP) {
        // Move the map.
        mapRenderer.moveCamera(new Point((int) event.getX(0) - previousContact[0].x, (int) event
            .getY(0) - previousContact[0].y));
      }
      // And the user is specifying an orientation for a pose on the map.
      else if (currentInteractionMode == InteractionMode.SPECIFY_LOCATION) {
        // Set orientation of the goal pose.
        mapRenderer.updateUserGoal(mapRenderer.toOpenGLPose(goalContact,
            getGoalOrientation(event.getX(0), event.getY(0))));
      }
      // Store current contact position.
      previousContact[0].x = (int) event.getX(0);
      previousContact[0].y = (int) event.getY(0);
    }
    // If there are two contacts on the view.
    else if (event.getPointerCount() == 2) {
      // Zoom in/out based on the distance between locations of current
      // contacts and previous contacts.
      mapRenderer.zoomCamera(calcDistance(event.getX(0), event.getY(0), event.getX(1),
          event.getY(1))
            / calcDistance(previousContact[0].x, previousContact[0].y, previousContact[1].x,
                previousContact[1].y));
      // Update contact information.
      previousContact[0].x = (int) event.getX(0);
      previousContact[0].y = (int) event.getY(0);
      previousContact[1].x = (int) event.getX(1);
      previousContact[1].y = (int) event.getY(1);
      // Prevent transition into SPECIFY_GOAL mode.
      longPressHandler.removeCallbacks(longPressRunnable);
    }
    requestRender();
  }

  private void contactUp(MotionEvent event) {
    // If the user was trying to specify a pose and just lifted the contact then
    // publish the position based on the initial contact down location and the
    // orientation based on the current contact up location.
    if (poseFrameId != null && currentInteractionMode == InteractionMode.SPECIFY_LOCATION) {
      PoseStamped poseStamped = new PoseStamped();
      poseStamped.header.frame_id = poseFrameId;
      poseStamped.header.stamp = node.getCurrentTime();
      poseStamped.pose =
          mapRenderer.toOpenGLPose(goalContact, getGoalOrientation(event.getX(), event.getY()));
      // If the user was trying to specify an initial pose.
      if (initialPoseMode) {
        PoseWithCovarianceStamped poseWithCovarianceStamped = new PoseWithCovarianceStamped();
        poseWithCovarianceStamped.header.frame_id = poseFrameId;
        poseWithCovarianceStamped.pose.pose = poseStamped.pose;
        // Publish the initial pose.
        initialPose.publish(poseWithCovarianceStamped);
      } else {
        goalPublisher.publish(poseStamped);
      }
    }
    resetInteractionState();
  }

  private boolean contactDown(MotionEvent event) {
    boolean returnValue = true;
    // If it's been less than DOUBLE_TAP_TIME milliseconds since the last
    // contact down then the user just performed a double tap gesture.
    if (Calendar.getInstance().getTimeInMillis() - previousContactDownTime < DOUBLE_TAP_TIME) {
      mapRenderer.toggleCenterOnRobot();
      requestRender();
      // Further information from this contact is no longer needed.
      returnValue = false;
    } else {
      // Since this is not a double tap, start the timer to detect a
      // press and hold.
      longPressHandler.postDelayed(longPressRunnable, PRESS_AND_HOLD_TIME);
    }
    previousContact[0].x = (int) event.getX(0);
    previousContact[0].y = (int) event.getY(0);
    goalContact.x = previousContact[0].x;
    goalContact.y = previousContact[0].y;
    System.out.println("goal contact: " + goalContact);
    previousContactDownTime = Calendar.getInstance().getTimeInMillis();
    return returnValue;
  }

  private void resetInteractionState() {
    currentInteractionMode = InteractionMode.INVALID;
    longPressHandler.removeCallbacks(longPressRunnable);
    initialPoseMode = false;
    mapRenderer.userGoalVisible(false);
  }

  /**
   * Calculates the distance between the 2 specified points.
   */
  private float calcDistance(float x1, float y1, float x2, float y2) {
    return (float) (Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)));
  }

  /**
   * Returns the orientation of the specified point relative to
   * {@link #goalContact}.
   * 
   * @param x
   *          The x-coordinate of the contact in pixels on the view.
   * @param y
   *          The y-coordinate of the contact in pixels on the view.
   * @return The angle between passed coordinates and {@link #goalContact} in
   *         degrees (0 to 360).
   */
  private float getGoalOrientation(float x, float y) {
    return (float) Math.atan2(y - goalContact.y, x - goalContact.x);
  }
}