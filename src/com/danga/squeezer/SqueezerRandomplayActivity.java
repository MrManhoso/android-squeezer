/*
 * Copyright (c) 2009 Google Inc.  All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.danga.squeezer;


import java.util.Arrays;

import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;

import com.danga.squeezer.service.ISqueezeService;
import com.danga.squeezer.service.SqueezeService;

public class SqueezerRandomplayActivity extends ListActivity {
	private static final String TAG = SqueezerRandomplayActivity.class.getName();

	private ISqueezeService service;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setRandomplayMenu();
	}

    @Override
    public void onResume() {
        super.onResume();
        bindService(new Intent(this, SqueezeService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        Log.d(TAG, "did bindService; serviceStub = " + service);
    }

	private final ServiceConnection serviceConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ISqueezeService.Stub.asInterface(binder);
        }
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        };
    };

	@Override
    public void onPause() {
        super.onPause();
        if (serviceConnection != null) {
        	unbindService(serviceConnection);
        }
    }

	private void setRandomplayMenu() {
		String[] values = getResources().getStringArray(R.array.randomplay_items);
		int[] icons = new int[values.length];
		Arrays.fill(icons, R.drawable.icon_ml_random);
		icons[icons.length -1] = R.drawable.icon_ml_genres;
		setListAdapter(new IconRowAdapter(this, values, icons));
		getListView().setOnItemClickListener(onRandomplayItemClick);
	}

	private final OnItemClickListener onRandomplayItemClick = new OnItemClickListener() {
		public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
			if (position < RandomPlayType.values().length) {
				try {
					service.randomPlay(RandomPlayType.values()[position].toString());
				} catch (RemoteException e) {
	                Log.e(TAG, "Error registering list callback: " + e);
				}
				SqueezerActivity.show(SqueezerRandomplayActivity.this);
				return;
			}
			switch (position) {
			}
		}
	};

	static void show(Context context) {
        final Intent intent = new Intent(context, SqueezerRandomplayActivity.class);
        context.startActivity(intent);
    }

	public enum RandomPlayType {
		tracks,
		albums,
		contributors,
		year;
	}

}
