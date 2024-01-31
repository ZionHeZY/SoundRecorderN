package com.android.soundrecorder.actionmode;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;

public class ButtonWithPopupMenu extends Button {
    private PopupMenu mPopupMenu;

    public ButtonWithPopupMenu(Context context) {
        this(context, null);
    }

    public ButtonWithPopupMenu(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.actionDropDownStyle);
    }

    public ButtonWithPopupMenu(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void loadPopupMenu(int id, PopupMenu.OnMenuItemClickListener listener) {
        mPopupMenu = new PopupMenu(getContext(), this);
        mPopupMenu.getMenuInflater().inflate(id, mPopupMenu.getMenu());
        mPopupMenu.setOnMenuItemClickListener(listener);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
    }

    private void showMenu() {
        if (mPopupMenu != null) {
            mPopupMenu.show();
        }
    }

    public MenuItem findPopupMenuItem(int menuItemId) {
        if (mPopupMenu != null) {
            return mPopupMenu.getMenu().findItem(menuItemId);
        }
        return null;
    }
}
