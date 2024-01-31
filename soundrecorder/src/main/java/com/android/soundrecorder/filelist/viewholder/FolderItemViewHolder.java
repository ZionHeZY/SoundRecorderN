package com.android.soundrecorder.filelist.viewholder;

import android.view.View;

import com.android.soundrecorder.R;
import com.android.soundrecorder.filelist.listitem.BaseListItem;

import util.FileUtils;
import util.StorageUtils;


public class FolderItemViewHolder extends BaseViewHolder {
    public FolderItemViewHolder(View itemView, int rootLayoutId) {
        super(itemView, rootLayoutId);
    }

    @Override
    public void setItem(BaseListItem item) {
        super.setItem(item);

        String title = FileUtils.getLastFileName(item.getPath(), false);
        if (title.equals(StorageUtils.FM_RECORDING_FOLDER_NAME)) {
            setTitle(mRootView.getContext().getApplicationContext().getResources()
                    .getString(R.string.file_list_FM));
        } else if (title.equals(StorageUtils.CALL_RECORDING_FOLDER_NAME)) {
            setTitle(mRootView.getContext().getApplicationContext().getResources()
                    .getString(R.string.file_list_call));
        } else {
            setTitle(title);
        }
    }
}
