package com.android.soundrecorder.filelist.viewholder;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.android.soundrecorder.R;
import com.android.soundrecorder.filelist.listitem.BaseListItem;


public class BaseViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener,
        View.OnLongClickListener {
    protected View mRootView;
    protected TextView mTitleView;
    protected CheckBox mCheckBox;
    protected boolean mInSelectionMode = false;
    protected BaseListItem mItem;

    public interface ViewHolderItemListener {
        void onItemChecked(BaseListItem item, boolean isChecked);

        void onItemClick(BaseListItem item);

        void onItemLongClick(BaseListItem item);
    }

    protected ViewHolderItemListener mItemListener;

    public void setViewHolderItemListener(ViewHolderItemListener listener) {
        mItemListener = listener;
    }

    public BaseViewHolder(View itemView, int rootLayoutId) {
        super(itemView);
        mRootView = itemView.findViewById(rootLayoutId);
        if (mRootView != null) {
            mRootView.setOnClickListener(this);
            mRootView.setOnLongClickListener(this);
        }
        mTitleView = (TextView) itemView.findViewById(R.id.list_item_title);
        mCheckBox = (CheckBox) itemView.findViewById(R.id.list_check_box);
        if (mCheckBox != null) {
            mCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (mItemListener != null) {
                        mItemListener.onItemChecked(mItem, isChecked);
                    }
                }
            });
        }
    }

    public void setItem(BaseListItem item) {
        mItem = item;
        setTitle(item.getTitle());
        if (mItem.isSelectable()) {
            setSelected(mInSelectionMode && item.isChecked());
        } else {
            if (mCheckBox != null) {
                mCheckBox.setVisibility(View.INVISIBLE);
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (mInSelectionMode) {
            setSelected(!isSelected());
        } else {
            if (mItemListener != null) {
                mItemListener.onItemClick(mItem);
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (!mItem.isSelectable() || mInSelectionMode) {
            return false;
        }
        if (mItemListener != null) {
            mItemListener.onItemLongClick(mItem);
            return true;
        }
        return false;
    }

    public void setTitle(String title) {
        if (mTitleView != null) {
            mTitleView.setText(title);
        }
    }

    public void setSelectionMode(boolean inSelection) {
        mInSelectionMode = inSelection;
        if (mCheckBox == null) return;
        mCheckBox.setVisibility(mInSelectionMode ? View.VISIBLE : View.INVISIBLE);
    }

    public void setSelected(boolean selected) {
        if (mCheckBox == null) return;
        mCheckBox.setChecked(selected);
    }

    public boolean isSelected() {
        return mCheckBox != null && mCheckBox.isChecked();
    }
}
