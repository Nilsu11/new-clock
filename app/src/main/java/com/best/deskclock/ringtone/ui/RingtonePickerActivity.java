/*
 *     Copyright (C) 2023  Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.best.deskclock.ringtone.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.best.deskclock.R;
import com.best.deskclock.Utils;
import com.best.deskclock.alarms.AlarmUpdateHandler;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.ringtone.RingtoneItem;
import com.best.deskclock.ringtone.RingtonePreviewKlaxon;
import com.google.android.material.tabs.TabLayout;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RingtonePickerActivity extends AppCompatActivity {
    /** Key to an extra that defines resource id to the title of this activity. */
    private static final String EXTRA_TITLE = "extra_title";

    /** Key to an extra that identifies the alarm to which the selected ringtone is attached. */
    private static final String EXTRA_ALARM_ID = "extra_alarm_id";

    /** Key to an extra that identifies the selected ringtone. */
    private static final String EXTRA_RINGTONE_URI = "extra_ringtone_uri";

    /** Key to an extra that defines the uri representing the default ringtone. */
    private static final String EXTRA_IS_SLEEP = "extra_is_sleep";

    /**
     * @return an intent that launches the ringtone picker to edit the ringtone of the given
     *      {@code alarm}
     */
    public static Intent createAlarmRingtonePickerIntent(Context context, Alarm alarm) {
        return new Intent(context, RingtonePickerActivity.class)
                .putExtra(EXTRA_TITLE, R.string.alarm_sound)
                .putExtra(EXTRA_ALARM_ID, alarm.id)
                .putExtra(EXTRA_RINGTONE_URI, alarm.alert);
    }

    public static Intent createAlarmRingtonePickerIntentForSettings(Context context) {
        final DataModel dataModel = DataModel.getDataModel();
        return new Intent(context, RingtonePickerActivity.class)
                .putExtra(EXTRA_TITLE, R.string.default_alarm_ringtone_title)
                .putExtra(EXTRA_RINGTONE_URI, dataModel.getAlarmRingtoneUriFromSettings());
    }

    /**
     * @return an intent that launches the ringtone picker to edit the ringtone of all timers
     */
    public static Intent createTimerRingtonePickerIntent(Context context) {
        final DataModel dataModel = DataModel.getDataModel();
        return new Intent(context, RingtonePickerActivity.class)
                .putExtra(EXTRA_TITLE, R.string.timer_sound)
                .putExtra(EXTRA_RINGTONE_URI, dataModel.getTimerRingtoneUri());
    }

    public RingtoneItem mCurrentItem;

    public int mSelectedTab = 0;
    private long mAlarmId;
    boolean sIsSleep;

    private TabLayout mTabLayout;
    public TabsAdapter mAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Utils.applyTheme(this);

        setContentView(R.layout.ringtone_picker_activity);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Intent intent = getIntent();

        // Enable title and home button by default
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setTitle(intent.getIntExtra(EXTRA_TITLE, R.string.app_label));
        }

        sIsSleep = intent.getBooleanExtra(EXTRA_IS_SLEEP, false);
        mAlarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1);
        mCurrentItem = new RingtoneItem();

        mCurrentItem.uri = intent.getParcelableExtra(EXTRA_RINGTONE_URI).toString();

        Context context = getApplicationContext();
        // setup tabs
        ViewPager viewPager = findViewById(R.id.fragment_viewpager);
        viewPager.setOffscreenPageLimit(9999);
        mAdapter = new TabsAdapter(this, viewPager);
        // add tabs
        mAdapter.addTab(AlarmsFragment.class, context.getString(R.string.device_sounds));
        mAdapter.addTab(SongsFragment.class, context.getString(R.string.tab_songs));
        mAdapter.addTab(AlbumsFragment.class, context.getString(R.string.tab_albums));
        mAdapter.addTab(ArtistsFragment.class, context.getString(R.string.tab_artists));
        mAdapter.addTab(PlaylistsFragment.class, context.getString(R.string.tab_playlists));
        mTabLayout = findViewById(R.id.tab_layout);
        mTabLayout.setSelectedTabIndicatorColor(getColor(R.color.md_theme_surface));
        viewPager.setAdapter(mAdapter);
        mTabLayout.setupWithViewPager(viewPager);
        mTabLayout.selectTab(mTabLayout.getTabAt(mSelectedTab));
        mTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                mSelectedTab = tab.getPosition();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });

        getWindow().setNavigationBarColor(getColor(R.color.md_theme_surface));
    }

    @Override
    protected void onPause() {
        if (Uri.parse(mCurrentItem.uri) != null) {
            if (mAlarmId != -1) {
                final Context context = getApplicationContext();
                final ContentResolver cr = getContentResolver();

                // Start a background task to fetch the alarm whose ringtone must be updated.
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Handler handler = new Handler(Looper.getMainLooper());
                executor.execute(() -> {
                    final Alarm alarm = Alarm.getAlarm(cr, mAlarmId);
                    if (alarm != null) {
                        alarm.alert = Uri.parse(mCurrentItem.uri);

                        handler.post(() -> {
                            // Start a second background task to persist the updated alarm.
                            new AlarmUpdateHandler(context, null, null)
                                    .asyncUpdateAlarm(alarm, false, true);
                        });
                    }
                });
            } else if (getSupportActionBar().getTitle().equals(getString(R.string.default_alarm_ringtone_title))) {
                DataModel.getDataModel().setAlarmRingtoneUriFromSettings(Uri.parse(mCurrentItem.uri));
            } else {
                DataModel.getDataModel().setTimerRingtoneUri(Uri.parse(mCurrentItem.uri));
            }
        }

        RingtonePreviewKlaxon.stop(this);

        super.onPause();
    }

    /**
     * Adapter for wrapping together the ActionBar's tab with the ViewPager
     */
    class TabsAdapter extends FragmentPagerAdapter
            implements ViewPager.OnPageChangeListener {

        private final ArrayList<TabInfo> mTabs = new ArrayList<>();
        private final Context mContext;

        final class TabInfo {
            public final Class<?> mClass;
            public final CharSequence mTitle;

            TabInfo(Class<?> clss, CharSequence title) {
                mClass = clss;
                mTitle = title;
            }
        }

        public TabsAdapter(AppCompatActivity activity, ViewPager pager) {
            super(activity.getSupportFragmentManager());
            mContext = activity;
            pager.setAdapter(this);
        }

        @NonNull
        @Override
        public BasePickerFragment getItem(int position) {
            // Because this public method is called outside many times,
            // check if it exits first before creating a new one.
            final String name = makeFragmentName(R.id.fragment_viewpager, position);
            BasePickerFragment fragment = (BasePickerFragment) getSupportFragmentManager().findFragmentByTag(name);
            if (fragment == null) {
                TabInfo info = mTabs.get(position);
                Class<? extends Fragment> clazz = FragmentFactory.loadFragmentClass(
                        mContext.getClassLoader(), info.mClass.getName());
                try {
                    fragment = (BasePickerFragment) clazz.getConstructor().newInstance();
                } catch (IllegalAccessException | InstantiationException |
                         InvocationTargetException | NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
            }
            return fragment;
        }

        /**
         * Copied from:
         * android/frameworks/support/v13/java/android/support/v13/app/FragmentPagerAdapter.java#94
         * Create unique name for the fragment so fragment manager knows it exist.
         */
        private String makeFragmentName(int viewId, int index) {
            return "android:switcher:" + viewId + ":" + index;
        }

        @Override
        public int getCount() {
            return mTabs.size();
        }

        @Override
        public CharSequence getPageTitle (int position) {
            TabInfo info = mTabs.get(position);
            return info.mTitle;
        }

        public void addTab(Class<?> clss, CharSequence title) {
            TabInfo info = new TabInfo(clss, title);
            mTabs.add(info);
            notifyDataSetChanged();
        }

        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            // Do nothing
        }

        @Override
        public void onPageSelected(int position) {
            mSelectedTab = position;
            mTabLayout.selectTab(mTabLayout.getTabAt(mSelectedTab));
        }

        @Override
        public void onPageScrollStateChanged(int state) {
            // Do nothing
        }

    }
}
