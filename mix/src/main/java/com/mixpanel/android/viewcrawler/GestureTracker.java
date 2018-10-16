package com.mixpanel.android.viewcrawler;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;

import com.mixpanel.android.mpmetrics.MixpanelAPI;

/**
 * Tracking ABTesting Gestures
 * $ab_gesture1 = 4 times two finger tap when last tap is hold for 3 seconds
 * $ab_gesture2 = 5 times two finger tap
 **/
public class GestureTracker {

    public GestureTracker(MixpanelAPI mMixpanel, Activity parent) {
        trackGestures(mMixpanel, parent);
    }

    private void trackGestures(final MixpanelAPI mMixpanel, final Activity parent) {
        parent.getWindow().getDecorView().
                setOnTouchListener(getGestureTrackerTouchListener(mMixpanel));
    }

    private View.OnTouchListener getGestureTrackerTouchListener(final MixpanelAPI mMixpanel) {
        return new View.OnTouchListener() {


            /**
             * 第二根手指落下的时间
             */
            private long mSecondFingerTimeDown = -1;
            /**
             * 第一根手指落下的时间
             */
            private long mFirstToSecondFingerDifference = -1;
            private int mGestureSteps = 0;
            private long mTimePassedBetweenTaps = -1;
            /**
             * 是否同时按下俩根手指
             */
            private boolean mDidTapDownBothFingers = false;
            //俩根手指落下的时间差
            private final int TIME_BETWEEN_FINGERS_THRESHOLD = 100;
            private final int TIME_BETWEEN_TAPS_THRESHOLD = 1000;
            private final int TIME_FOR_LONG_TAP = 2500;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                //如果触摸点超过2个  直接重置
                if (event.getPointerCount() > 2) {
                    resetGesture();
                    return false;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mFirstToSecondFingerDifference = System.currentTimeMillis();
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        //判断第二根手指落下的时间和 第一根手指落下的时间  之差 是否小于 100ms
                        if ((System.currentTimeMillis() - mFirstToSecondFingerDifference) < TIME_BETWEEN_FINGERS_THRESHOLD) {
                            if (System.currentTimeMillis() - mTimePassedBetweenTaps > TIME_BETWEEN_TAPS_THRESHOLD) {
                                resetGesture();
                            }
                            //赋值第二根手指落下时间
                            mSecondFingerTimeDown = System.currentTimeMillis();
                            //同时落下俩根手指
                            mDidTapDownBothFingers = true;
                        } else {
                            //重置参数
                            resetGesture();
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        if (mDidTapDownBothFingers) {
                            mFirstToSecondFingerDifference = System.currentTimeMillis();
                        } else {
                            resetGesture();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        // 第一根抬起的手指,与屏幕接触的时间 小于 100ms
                        if ((System.currentTimeMillis() - mFirstToSecondFingerDifference) < TIME_BETWEEN_FINGERS_THRESHOLD) {
                            //第二根抬起的手指,与屏幕接触的时间 大于2500
                            if ((System.currentTimeMillis() - mSecondFingerTimeDown) >= TIME_FOR_LONG_TAP) {
                                // Long Tap
                                if (mGestureSteps == 3) {
                                    mMixpanel.track("$ab_gesture1");
                                    resetGesture();
                                }
                                mGestureSteps = 0;
                            } else {
                                // Short Tap
                                mTimePassedBetweenTaps = System.currentTimeMillis();
                                if (mGestureSteps < 4) {
                                    //双击次数++
                                    mGestureSteps += 1;
                                } else if (mGestureSteps == 4) {
                                    mMixpanel.track("$ab_gesture2");
                                    resetGesture();
                                } else {
                                    resetGesture();
                                }
                            }
                        }
                        break;
                }
                return false;
            }

            /**
             * 重置参数
             */
            private void resetGesture() {
                mFirstToSecondFingerDifference = -1;
                mSecondFingerTimeDown = -1;
                mGestureSteps = 0;
                mTimePassedBetweenTaps = -1;
                mDidTapDownBothFingers = false;
            }

        };
    }

}
