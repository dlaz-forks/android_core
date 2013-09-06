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

package org.ros.android.android_tutorial_camera;

import android.util.Log;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import org.ros.address.InetAddressFactory;
import org.ros.android.RosActivity;
import org.ros.android.view.camera.RosCameraPreviewView;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMainExecutor;

import edu.oregonstate.robotics.RosSensorIO;

/**
 * @author ethan.rublee@gmail.com (Ethan Rublee)
 * @author damonkohler@google.com (Damon Kohler)
 */
public class MainActivity extends RosActivity {
	private int cameraId;
	private RosCameraPreviewView rosCameraPreviewView;
	private RosSensorIO sensorIO;
	private String LOG_TAG = "camera_tutorial";
	public MainActivity() {
		super("CameraTutorial", "CameraTutorial");
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setContentView(R.layout.main);
		rosCameraPreviewView = (RosCameraPreviewView) findViewById(R.id.ros_camera_preview_view);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		Log.i("camera_tutorial", "Touch Event");
		if (event.getAction() == MotionEvent.ACTION_UP) {
			int numberOfCameras = Camera.getNumberOfCameras();
			final Toast toast;
			if (numberOfCameras > 1) {
				cameraId = (cameraId + 1) % numberOfCameras;
				rosCameraPreviewView.releaseCamera();
				rosCameraPreviewView.setCamera(Camera.open(cameraId));
				toast = Toast.makeText(this, "Switching cameras.", Toast.LENGTH_SHORT);
			} else {
				toast = Toast.makeText(this, "No alternative cameras to switch to.", Toast.LENGTH_SHORT);
			}
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					toast.show();
				}
			});
		}
		return true;
	}

	@Override
	protected void init(NodeMainExecutor nodeMainExecutor) {
		Log.i("camera_tutorial", "INITING!");
		cameraId = 0;
		rosCameraPreviewView.setCamera(Camera.open(cameraId));
		NodeConfiguration nodeConfiguration =
				NodeConfiguration.newPublic(InetAddressFactory.newNonLoopback().getHostAddress());
		Log.i(LOG_TAG, "Getting master URI");
		nodeConfiguration.setMasterUri(getMasterUri());
		nodeConfiguration.setNodeName("ros_camera_preview_view");
		nodeMainExecutor.execute(rosCameraPreviewView, nodeConfiguration);
				
		this.sensorIO = new RosSensorIO((SensorManager) getSystemService(Context.SENSOR_SERVICE), getMasterUri().getHost(), 9999);
		Thread clientThread = new Thread(this.sensorIO);
		Log.i(LOG_TAG, "Starting SensorIO");
		clientThread.start();

	}
}
