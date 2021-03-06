/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    public static final String PATH_WEATHER = "/weather";
    public static final String KEY_TEMP_HIGH = "com.example.android.sunshine.app.KEY_TEMP_HIGH";
    public static final String KEY_TEMP_LOW = "com.example.android.sunshine.app.KEY_TEMP_LOW";
    public static final String KEY_WEATHER_ICON = "com.example.android.sunshine.app.KEY_WEATHER_ICON";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private final Point mDisplaySize = new Point();
        private final SimpleDateFormat mFullTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        private final SimpleDateFormat mShortTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat mDateFormat = new SimpleDateFormat("EEE MMM dd yyyy", Locale.getDefault());
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;
        private boolean mInAmbientMode;
        private Date mDate;
        private int mSpecW, mSpecH;
        private View mLayout;
        private int mBackgroundColor;
        private LinearLayout mBackground;
        private TextView mTimeTextView;
        private TextView mDateTextView;
        private ImageView mWeatherIcon;
        private TextView mHighTempTextView;
        private TextView mLowTempTextView;

        private SunshineWeatherUpdater mWeatherUpdater;
        private String mHighTemp;
        private String mLowTemp;
        private Bitmap mIconBitmap;

        @SuppressLint("InflateParams")
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.START)
                    .setStatusBarGravity(Gravity.TOP | Gravity.END)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            // Inflate layout from XML rather than directly draw on canvas, using method described
            // at https://sterlingudell.wordpress.com/2015/05/10/layout-based-watch-faces-for-android-wear/
            // Layout is inflated with no parent root, so this must be manually resolved in onDraw()
            LayoutInflater layoutInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mLayout = layoutInflater.inflate(R.layout.watchface_rect, null);

            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            display.getSize(mDisplaySize);

            mSpecW = View.MeasureSpec.makeMeasureSpec(mDisplaySize.x, View.MeasureSpec.EXACTLY);
            mSpecH = View.MeasureSpec.makeMeasureSpec(mDisplaySize.y, View.MeasureSpec.EXACTLY);

            mBackground = (LinearLayout) mLayout.findViewById(R.id.watchface_background);
            mTimeTextView = (TextView) mLayout.findViewById(R.id.time_text);
            mDateTextView = (TextView) mLayout.findViewById(R.id.date_text);
            mWeatherIcon = (ImageView) mLayout.findViewById(R.id.weather_icon);
            mHighTempTextView = (TextView) mLayout.findViewById(R.id.high_temp_text);
            mLowTempTextView = (TextView) mLayout.findViewById(R.id.low_temp_text);

            mBackgroundColor = ContextCompat.getColor(SunshineWatchFace.this, R.color.md_light_blue_500);

            mWeatherUpdater = new SunshineWeatherUpdater(this);
            mWeatherUpdater.register();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mWeatherUpdater.unregister();
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mWeatherUpdater.register();
            } else {
                mWeatherUpdater.unregister();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mInAmbientMode != inAmbientMode) {
                mInAmbientMode = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeTextView.getPaint().setAntiAlias(!mInAmbientMode);
                    mDateTextView.getPaint().setAntiAlias(!mInAmbientMode);
                    mHighTempTextView.getPaint().setAntiAlias(!mInAmbientMode);
                    mLowTempTextView.getPaint().setAntiAlias(!mInAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type).
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mDate = Calendar.getInstance().getTime();

            mTimeTextView.setText(getFormattedTime());
            mDateTextView.setText(mDateFormat.format(mDate.getTime()));
            mHighTempTextView.setText(mHighTemp);
            mLowTempTextView.setText(mLowTemp);
            mWeatherIcon.setImageBitmap(mIconBitmap);

            if (isInAmbientMode()) {
                mBackground.setBackgroundColor(Color.BLACK);
                mWeatherIcon.setVisibility(View.GONE);
            } else {
                mBackground.setBackgroundColor(mBackgroundColor);
                mWeatherIcon.setVisibility(View.VISIBLE);
            }

            mLayout.measure(mSpecW, mSpecH);
            mLayout.layout(0, 0, mLayout.getMeasuredWidth(), mLayout.getMeasuredHeight());

            mLayout.draw(canvas);
        }

        public void updateWeather(DataMap dataMap) {
            Log.d("SunshineWatchFace", "updateWeather: Updated temperatures");
            if (dataMap != null) {
                if (dataMap.containsKey(KEY_TEMP_HIGH)
                        && dataMap.containsKey(KEY_TEMP_LOW)) {
                    mHighTemp = dataMap.getString(KEY_TEMP_HIGH);
                    mLowTemp = dataMap.getString(KEY_TEMP_LOW);
                    mWeatherUpdater.loadBitmapFromAsset(dataMap.getAsset(KEY_WEATHER_ICON));
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void setWeatherIcon(Bitmap icon) {
            mIconBitmap = icon;
        }

        @NonNull
        private SpannableStringBuilder getFormattedTime() {
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            String rawTime = mInAmbientMode
                    ? mShortTimeFormat.format(mDate.getTime())
                    : mFullTimeFormat.format(mDate.getTime());
            final SpannableStringBuilder time = new SpannableStringBuilder(rawTime);
            time.setSpan(new StyleSpan(Typeface.BOLD), 0, rawTime.indexOf(":"), 0);
            return time;
        }

        /**
         * Encapsulation of sync listeners on the DataApi used to trigger a weather update
         */
        class SunshineWeatherUpdater implements GoogleApiClient.ConnectionCallbacks,
                GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {

            private static final long TIMEOUT_MS = 2000;

            private final String TAG = SunshineWeatherUpdater.class.getSimpleName();
            private final GoogleApiClient mGoogleApiClient;
            private final Engine mSunshineWatchFaceEngine;

            public SunshineWeatherUpdater(SunshineWatchFace.Engine sunshineWatchFaceEngine) {
                mSunshineWatchFaceEngine = sunshineWatchFaceEngine;
                mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                        .addConnectionCallbacks(this)
                        .addOnConnectionFailedListener(this)
                        .addApi(Wearable.API)
                        .build();
            }

            @Override
            public void onConnected(@Nullable Bundle bundle) {
                Log.d(TAG, "onConnected: Watchface GoogleAPI client connected");
                Wearable.DataApi.addListener(mGoogleApiClient, this);
            }

            @Override
            public void onConnectionSuspended(int i) {
                Log.d(TAG, "onConnectionSuspended: Watchface GoogleAPI client suspended");
            }

            @Override
            public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                Log.e(TAG, "onConnectionFailed: Watchface GoogleAPI client could not connect");
            }

            @Override
            public void onDataChanged(DataEventBuffer dataEventBuffer) {
                Log.d(TAG, "onDataChanged: New data detected");
                for (DataEvent event : dataEventBuffer) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        // DataItem changed
                        DataItem item = event.getDataItem();
                        if (item.getUri().getPath().compareTo(PATH_WEATHER) == 0) {
                            // Retrieve data map and send it back to engine to update weather info
                            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                            mSunshineWatchFaceEngine.updateWeather(dataMap);
                        }
                    } else if (event.getType() == DataEvent.TYPE_DELETED) {
                        // DataItem deleted
                    }
                }
            }

            public void register() {
                Log.d(TAG, "register: Connecting client");
                mGoogleApiClient.connect();
            }

            public void unregister() {
                Log.d(TAG, "unregister: Disconnecting client");
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }

            public void loadBitmapFromAsset(Asset asset) {
                new LoadBitmapFromAssetTask().execute(asset);
            }

            public class LoadBitmapFromAssetTask extends AsyncTask<Asset, Void, Bitmap> {

                @Override
                protected Bitmap doInBackground(Asset... params) {
                    Asset asset = params[0];
                    if (asset == null) {
                        throw new IllegalArgumentException("Asset must be non-null");
                    }
                    ConnectionResult result =
                            mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!result.isSuccess()) {
                        return null;
                    }
                    // convert asset into a file descriptor and block until it's ready
                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();
                    mGoogleApiClient.disconnect();

                    if (assetInputStream == null) {
                        Log.w(TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    // decode the stream into a bitmap
                    return BitmapFactory.decodeStream(assetInputStream);
                }

                @Override
                protected void onPostExecute(Bitmap bitmap) {
                    Log.d(TAG, "Bitmap found: " + bitmap);
                    if (mSunshineWatchFaceEngine != null) {
                        mSunshineWatchFaceEngine.setWeatherIcon(bitmap);
                    }
                }
            }
        }
    }
}
