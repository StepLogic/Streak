package com.trend.streak.utils;

import android.content.Context;
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.View;

import com.trend.streak.R;


/**
 * Created by Gaurav on 3/3/2018.
 */
public class FocusIndicatorView extends View {

    public Point mLocationPoint;

    public FocusIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void setDrawable(int resid) {
        this.setBackgroundResource(resid);
    }
    public void setLocation(float x,float y){
        this.setTranslationX(x);
        this.setTranslationY(y);
    }

    public void showStart() {
        setDrawable(R.drawable.focus_indicator);
    }

    public void clear() {
        setBackgroundDrawable(null);
    }

}