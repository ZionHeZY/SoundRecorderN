package com.android.soundrecorder.filelist;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.View;

public class FileListRecyclerView extends RecyclerView {
    private View mEmptyView;

    private AdapterDataObserver mEmptyDataObserver = new AdapterDataObserver() {
        @Override
        public void onChanged() {
            showEmptyViewIfNeed();
        }
    };

    public FileListRecyclerView(Context context) {
        this(context, null);
    }

    public FileListRecyclerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FileListRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void setAdapter(Adapter adapter) {
        super.setAdapter(adapter);

        if (adapter != null) {
            adapter.registerAdapterDataObserver(mEmptyDataObserver);
        }

        mEmptyDataObserver.onChanged();
    }

    public boolean isAdapterEmpty() {
        Adapter adapter = getAdapter();
        return (adapter == null || adapter.getItemCount() == 0);
    }

    private void showEmptyViewIfNeed() {
        if (mEmptyView == null)
            return;
        if (isAdapterEmpty()) {
            mEmptyView.setVisibility(View.VISIBLE);
            FileListRecyclerView.this.setVisibility(View.GONE);
        } else {
            mEmptyView.setVisibility(View.GONE);
            FileListRecyclerView.this.setVisibility(View.VISIBLE);
        }
    }

    public void setEmptyView(View emptyView) {
        mEmptyView = emptyView;
        showEmptyViewIfNeed();
    }
}
