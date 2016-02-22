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

package com.example.android.sunshine.watchface;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
        private boolean mInAmbientMode;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;

        private Date mDate;
        private SimpleDateFormat mFullTimeFormat;
        private SimpleDateFormat mShortTimeFormat;
        private SimpleDateFormat mDateFormat;

        private int mSpecW, mSpecH;
        private final Point mDisplaySize = new Point();

        private View mLayout;
        private int mBackgroundColor;
        private LinearLayout mBackground;
        private TextView mTimeTextView;
        private TextView mDateTextView;
        private ImageView mWeatherIcon;
        private TextView mHighTempTextView;
        private TextView mLowTempTextView;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mFullTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            mShortTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            mDateFormat = new SimpleDateFormat("EEE MMM dd yyyy", Locale.getDefault());

            // Inflate layout from XML rather than directly draw on canvas, using method described
            // at https://sterlingudell.wordpress.com/2015/05/10/layout-based-watch-faces-for-android-wear/
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
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // TODO Adjust text size for round and rectangular faces

            // Load resources that have alternate values for round watches.
            // Resources resources = SunshineWatchFace.this.getResources();
            // boolean isRound = insets.isRound();
            // float textSize = resources.getDimension(isRound
            //        ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
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

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            String time = mInAmbientMode
                    ? mShortTimeFormat.format(mDate.getTime())
                    : mFullTimeFormat.format(mDate.getTime());
            String date = mDateFormat.format(mDate.getTime());

            // TODO update temperatures using a service
            String high = "25\u00B0";
            String low = "16\u00B0";

            mTimeTextView.setText(time);
            mDateTextView.setText(date);
            mHighTempTextView.setText(high);
            mLowTempTextView.setText(low);

            // TODO update weather icon using a service
            // mWeatherIcon.setImageBitmap(weatherIcon);

            if (isInAmbientMode()) {
                mBackground.setBackgroundColor(Color.BLACK);
            } else {
                mBackground.setBackgroundColor(mBackgroundColor);
            }

            mLayout.measure(mSpecW, mSpecH);
            mLayout.layout(0, 0, mLayout.getMeasuredWidth(), mLayout.getMeasuredHeight());

            mLayout.draw(canvas);
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
    }
}
