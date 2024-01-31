package com.android.soundrecorder.filelist;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.soundrecorder.R;
import com.android.soundrecorder.filelist.listitem.BaseListItem;
import com.android.soundrecorder.filelist.listitem.FolderItem;
import com.android.soundrecorder.filelist.listitem.MediaItem;
import com.android.soundrecorder.filelist.viewholder.BaseViewHolder;
import com.android.soundrecorder.filelist.viewholder.FolderItemViewHolder;
import com.android.soundrecorder.filelist.viewholder.MediaItemViewHolder;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import util.DatabaseUtils;
import util.FileUtils;

public class FileListRecyclerAdapter extends RecyclerView.Adapter {
    private String[] mTargetFolderArray;
    private String[] mTargetSourceArray;

    private ContentResolver mContentResolver;

    // store all items
    private List<BaseListItem> mItemsList = new ArrayList<>();

    private boolean mInSelectionMode = false;

    public interface ItemListener {
        void openItem(BaseListItem item);

        void closeItem();

        MediaItem getPlayingItem();

        void updatePlayerItem(MediaItem item);
    }

    private ItemListener mItemListener;

    public void setItemListener(ItemListener listener) {
        mItemListener = listener;
    }

    public interface ActionModeListener {
        void showActionMode();

        void exitActionMode();

        void setSelectedCount(int selectedCount, int totalCount, List<BaseListItem> items);
    }

    private ActionModeListener mActionModeListener;

    public void setActionModeListener(ActionModeListener listener) {
        mActionModeListener = listener;
    }

    public FileListRecyclerAdapter(Context context, String[] targetFolderArray,
                                   String[] targetSourceArray) {
        mContentResolver = context.getContentResolver();
        mTargetFolderArray = targetFolderArray;
        mTargetSourceArray = targetSourceArray;
    }

    @Override
    public int getItemViewType(int position) {
        if (mItemsList != null && mItemsList.size() > position) {
            return mItemsList.get(position).getItemType();
        }
        return BaseListItem.TYPE_NONE;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        //boolean isHH=false;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (viewType == BaseListItem.TYPE_FOLDER) {
            View folderItem = LayoutInflater.from(viewGroup.getContext()).inflate(
                    R.layout.file_list_item_folder, null);
            folderItem.setLayoutParams(lp);
            return new FolderItemViewHolder(folderItem, R.id.file_list_folder_layout);
        } else {
            View recordingItem = LayoutInflater.from(viewGroup.getContext()).inflate(
                    R.layout.file_list_item_recording, null);
            recordingItem.setLayoutParams(lp);
            //add by hezhongyang @20161129 for bug 132155 start
            //String strTimeFormat  = Settings.System.getString(mContentResolver,
            //Settings.System.TIME_12_24);
            boolean is24 = DateFormat.is24HourFormat(viewGroup.getContext());
            //isHH=strTimeFormat.equals("24");
            return new MediaItemViewHolder(recordingItem, R.id.file_list_recording_layout, is24);
            //add by hezhongyang @20161129 for bug 132155 end
        }
    }

    @Override
    public void onBindViewHolder(final RecyclerView.ViewHolder viewHolder, final int position) {
        if (viewHolder instanceof BaseViewHolder) {
            if (mItemsList != null && mItemsList.size() > position) {
                ((BaseViewHolder) viewHolder).setSelectionMode(mInSelectionMode);
                mItemsList.get(position);
                ((BaseViewHolder) viewHolder).setItem(mItemsList.get(position));
                ((BaseViewHolder) viewHolder)
                        .setViewHolderItemListener(new BaseViewHolder.ViewHolderItemListener() {
                            @Override
                            public void onItemChecked(BaseListItem item, boolean isChecked) {
                                changeSelectedState(viewHolder.getAdapterPosition(), isChecked);
                            }

                            @Override
                            public void onItemClick(BaseListItem item) {
                                if (mItemListener != null) {
                                    mItemListener.openItem(item);
                                }
                            }

                            @Override
                            public void onItemLongClick(BaseListItem item) {
                                enterSelectionMode(viewHolder.getAdapterPosition());
                            }
                        });

                setMediaItemPlayStatusListener(position);
            }
        }
    }

    @Override
    public int getItemCount() {
        return mItemsList == null ? 0 : mItemsList.size();
    }

    public int getSelectableItemCount() {
        int count = 0;
        for (BaseListItem item : mItemsList) {
            count = count + (item.isSelectable() ? 1 : 0);
        }
        return count;
    }

    private int getSelectedItemCount() {
        return getSelectedItems().size();
    }

    public List<BaseListItem> getSelectedItems() {
        List<BaseListItem> items = new ArrayList<>();
        for (BaseListItem item : mItemsList) {
            if (item.isChecked()) {
                items.add(item);
            }
        }
        return items;
    }

    public void reload() {
        List<BaseListItem> resultList = new ArrayList<>();
        // find folder item
        if (mTargetFolderArray != null) {
            for (String folder : mTargetFolderArray) {
                boolean isEmpty = FileUtils.isFolderEmpty(folder);
                if (!isEmpty) {
                    long folderId = DatabaseUtils.getFolderId(mContentResolver, folder);
                    if (folderId != DatabaseUtils.NOT_FOUND) {
                        FolderItem item = new FolderItem(folderId, folder);
                        resultList.add(item);
                    }
                }
            }
        }

        // find recording item
        List<Long> sourceFolderIds = new ArrayList<>();
        if (mTargetSourceArray != null) {
            for (String folder : mTargetSourceArray) {
                long folderId = DatabaseUtils.getFolderId(mContentResolver, folder);
                if (folderId != DatabaseUtils.NOT_FOUND) {
                    sourceFolderIds.add(folderId);
                }
            }
        }

        if (sourceFolderIds.size() > 0) {
            Long ids[] = new Long[sourceFolderIds.size()];
            sourceFolderIds.toArray(ids);
            Cursor cursor = DatabaseUtils.getFolderCursor(mContentResolver, ids);
            if (cursor != null) {
                int len = cursor.getCount();
                for (int i = 0; i < len; i++) {
                    cursor.moveToNext();
                    WeakReference<Cursor> cursorWeakReference = new WeakReference<>(cursor);
                    MediaItem item = new MediaItem(cursorWeakReference.get());
                    resultList.add(item);
                }
                cursor.close();
            }
        }

        // update list item to mItemsList.
        for (BaseListItem item : resultList) {
            int index = mItemsList.indexOf(item);
            if (index >= 0) {
                item.copyFrom(mItemsList.get(index));
            }
        }
        mItemsList.clear();
        mItemsList.addAll(resultList);

        updatePlayerItem();

        updateSelectedCountInActionMode();

        notifyDataSetChanged();
    }

    private void updatePlayerItem() {
        if (mItemListener == null) return;
        MediaItem playingItem = mItemListener.getPlayingItem();
        if (playingItem == null || !FileUtils.exists(new File(playingItem.getPath()))) {
            mItemListener.closeItem();
        } else {
            int index = mItemsList.indexOf(playingItem);
            if (index >= 0) {
                BaseListItem item = mItemsList.get(index);
                if (item instanceof MediaItem) {
                    mItemListener.updatePlayerItem((MediaItem) item);
                }
            }
        }
    }

    private void enterSelectionMode(int startPosition) {
        if (mInSelectionMode) return;

        if (mItemListener != null) {
            mItemListener.closeItem();
        }

        if (mActionModeListener != null) {
            mActionModeListener.showActionMode();
        }
        mInSelectionMode = true;
        changeSelectedState(startPosition, true);
        notifyDataSetChanged();
    }

    public void leaveSelectionMode() {
        mInSelectionMode = false;
        int count = getItemCount();
        for (int i = 0; i < count; i++) {
            changeSelectedState(i, false);
        }
        notifyDataSetChanged();
    }

    private void updateSelectedCountInActionMode() {
        if (mActionModeListener != null) {
            int selectedCount = getSelectedItemCount();
            if (selectedCount == 0 || getSelectableItemCount() == 0) {
                mActionModeListener.exitActionMode();
            } else {
                mActionModeListener.setSelectedCount(selectedCount, getSelectableItemCount(),
                        getSelectedItems());
            }
        }
    }

    public void toggleSelectAllState() {
        if (!mInSelectionMode) return;
        boolean isAll = getSelectedItemCount() >= getSelectableItemCount();

        int count = getItemCount();
        for (int i = 0; i < count; i++) {
            changeSelectedState(i, !isAll);
        }
        notifyDataSetChanged();
    }

    private void changeSelectedState(int position, boolean checked) {
        BaseListItem item = mItemsList.get(position);
        item.setChecked(checked);

        if (mInSelectionMode) {
            updateSelectedCountInActionMode();
        }
    }

    public void notifyItemChanged(BaseListItem item) {
        if (mItemsList.contains(item)) {
            int position = mItemsList.indexOf(item);
            notifyItemChanged(position);
        }
    }

    public void removeItem(BaseListItem item) {
        if (mItemsList.contains(item)) {
            int position = mItemsList.indexOf(item);
            mItemsList.remove(item);
            notifyItemRemoved(position);
        }

        if (mInSelectionMode) {
            updateSelectedCountInActionMode();
        }
    }

    private void setMediaItemPlayStatusListener(final int position) {
        if (BaseListItem.TYPE_MEDIA_ITEM != getItemViewType(position)) {
            return;
        }

        BaseListItem baseItem = mItemsList.get(position);
        if ((baseItem == null) || !(baseItem instanceof MediaItem)) {
            return;
        }

        ((MediaItem) baseItem).setItemPlayStatusListener(
                new MediaItem.ItemPlayStatusListener() {

                    @Override
                    public void onPlayStatusChanged(MediaItem.PlayStatus status) {
                        FileListRecyclerAdapter.this.notifyItemChanged(position);
                    }
                }
        );
    }
}
