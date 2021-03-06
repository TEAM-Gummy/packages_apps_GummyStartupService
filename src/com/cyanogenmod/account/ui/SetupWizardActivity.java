/*
 * Copyright (C) 2013 The CyanogenMod Project
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

package com.cyanogenmod.account.ui;

import com.cyanogenmod.account.CMAccount;
import com.cyanogenmod.account.R;
import com.cyanogenmod.account.gcm.GCMUtil;
import com.cyanogenmod.account.setup.AbstractSetupData;
import com.cyanogenmod.account.setup.CMSetupWizardData;
import com.cyanogenmod.account.setup.Page;
import com.cyanogenmod.account.setup.PageList;
import com.cyanogenmod.account.setup.SetupDataCallbacks;
import com.cyanogenmod.account.util.CMAccountUtils;

import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.v13.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.View;
import android.widget.Button;

import java.util.List;

public class SetupWizardActivity extends Activity implements SetupDataCallbacks {

    private static final String TAG = SetupWizardActivity.class.getSimpleName();

    private static final String GOOGLE_SETUPWIZARD_PACKAGE = "com.google.android.setupwizard";
    private static final String KEY_SIM_MISSING_SHOWN = "sim-missing-shown";
    private static final String KEY_G_ACCOUNT_SHOWN = "g-account-shown";

    private static final int DIALOG_SIM_MISSING = 0;

    private ViewPager mViewPager;
    private CMPagerAdapter mPagerAdapter;

    private Button mNextButton;
    private Button mPrevButton;

    private PageList mPageList;

    private AbstractSetupData mSetupData;

    private final Handler mHandler = new Handler();

    private SharedPreferences mSharedPreferences;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.setup_main);
        ((CMAccount)AppGlobals.getInitialApplication()).disableStatusBar();
        mSharedPreferences = getSharedPreferences(CMAccount.SETTINGS_PREFERENCES, Context.MODE_PRIVATE);
        mSetupData = (AbstractSetupData)getLastNonConfigurationInstance();
        if (mSetupData == null) {
            mSetupData = new CMSetupWizardData(this);
        }

        if (savedInstanceState != null) {
            mSetupData.load(savedInstanceState.getBundle("data"));
        } else {
            mSharedPreferences.edit().putBoolean(KEY_SIM_MISSING_SHOWN, false).commit();
        }
        mNextButton = (Button) findViewById(R.id.next_button);
        mPrevButton = (Button) findViewById(R.id.prev_button);
        mSetupData.registerListener(this);
        mPagerAdapter = new CMPagerAdapter(getFragmentManager());
        mViewPager = (ViewPager) findViewById(R.id.pager);
        mViewPager.setPageTransformer(true, new DepthPageTransformer());
        mViewPager.setAdapter(mPagerAdapter);
        mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (position < mPageList.size()) {
                    onPageLoaded(mPageList.get(position));
                }
            }
        });
        mNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                doNext();
            }
        });
        mPrevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                doPrevious();
            }
        });
        onPageTreeChanged();
        removeUnNeededPages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        onPageTreeChanged();
        if (!CMAccountUtils.isNetworkConnected(this)) {
            CMAccountUtils.tryEnablingWifi(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mSetupData.unregisterListener(this);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return mSetupData;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBundle("data", mSetupData.save());
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        switch (id) {
            case DIALOG_SIM_MISSING:
                return new AlertDialog.Builder(this)
                        .setIcon(R.drawable.cid_confused)
                        .setTitle(R.string.setup_sim_missing)
                        .setMessage(R.string.sim_missing_summary)
                        .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                dialogInterface.dismiss();
                            }
                        })
                        .create();
            default:
                return super.onCreateDialog(id, args);
        }
    }

    @Override
    public void onBackPressed() {
        doPrevious();
    }

    public void doNext() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                final int currentItem = mViewPager.getCurrentItem();
                final Page currentPage = mPageList.get(currentItem);
                if (currentPage.getId() == R.string.setup_complete) {
                    finishSetup();
                } else {
                    mViewPager.setCurrentItem(currentItem + 1, true);
                }
            }
        });
    }

    public void doPrevious() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                final int currentItem = mViewPager.getCurrentItem();
                if (currentItem > 0) {
                    mViewPager.setCurrentItem(currentItem - 1, true);
                }
            }
        });
    }

    private void removeSetupPage(final Page page, boolean animate) {
        if (page == null || getPage(page.getKey()) == null || page.getId() == R.string.setup_complete) return;
        final int position = mViewPager.getCurrentItem();
        if (animate) {
            mViewPager.setCurrentItem(0);
            mSetupData.removePage(page);
            mViewPager.setCurrentItem(position, true);
        } else {
            mSetupData.removePage(page);
        }
        onPageLoaded(mPageList.get(position));
    }

    private void updateNextPreviousState() {
        final int position = mViewPager.getCurrentItem();
        mNextButton.setEnabled(position != mPagerAdapter.getCutOffPage());
        mPrevButton.setVisibility(position <= 0 ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    public void onPageLoaded(Page page) {
        mNextButton.setText(page.getNextButtonResId());
        if (page.isRequired()) {
            if (recalculateCutOffPage()) {
                mPagerAdapter.notifyDataSetChanged();
            }
        }
        if (page.getId() == R.string.setup_google_account) {
            // Only auto show the google account setup once.
            boolean shown = mSharedPreferences.getBoolean(KEY_G_ACCOUNT_SHOWN, false);
            if (!shown) {
                mSharedPreferences.edit().putBoolean(KEY_G_ACCOUNT_SHOWN, true).commit();
                doSimCheck();
                launchGoogleAccountSetup();
            }
        }
        updateNextPreviousState();
    }

    @Override
    public void onPageTreeChanged() {
        mPageList = mSetupData.getPageList();
        recalculateCutOffPage();
        mPagerAdapter.notifyDataSetChanged();
        updateNextPreviousState();
    }

    @Override
    public Page getPage(String key) {
        return mSetupData.findPage(key);
    }

    @Override
    public void onPageFinished(final Page page) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (page == null) {
                    doNext();
                } else {
                    switch (page.getId()) {
                        case R.string.setup_google_account:
                            removeSetupPage(page, false);
                            break;
                    }
                }
                onPageTreeChanged();
            }
        });
    }

    private boolean recalculateCutOffPage() {
        // Cut off the pager adapter at first required page that isn't completed
        int cutOffPage = mPageList.size();
        for (int i = 0; i < mPageList.size(); i++) {
            Page page = mPageList.get(i);
            if (page.isRequired() && !page.isCompleted()) {
                cutOffPage = i;
                break;
            }
        }

        if (mPagerAdapter.getCutOffPage() != cutOffPage) {
            mPagerAdapter.setCutOffPage(cutOffPage);
            return true;
        }

        return false;
    }

    private void removeUnNeededPages() {
        boolean pagesRemoved = false;
        Page page = mPageList.findPage(R.string.setup_google_account);
        if (page != null && (!GCMUtil.googleServicesExist(SetupWizardActivity.this) || accountExists(CMAccount.ACCOUNT_TYPE_GOOGLE))) {
            removeSetupPage(page, false);
            pagesRemoved = true;
        }
        if (pagesRemoved) {
            onPageTreeChanged();
        }
    }

    private void doSimCheck() {
        if (!mSharedPreferences.getBoolean(KEY_SIM_MISSING_SHOWN, false)) {
            if (CMAccountUtils.isGSMPhone(SetupWizardActivity.this) && CMAccountUtils.isSimMissing(SetupWizardActivity.this)) {
                //Delay the dialog so the animation can finish
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        showDialog(DIALOG_SIM_MISSING);
                    }
                }, 500);
            }
            mSharedPreferences.edit().putBoolean(KEY_SIM_MISSING_SHOWN, true).commit();
        }
    }

    private void disableSetupWizards(Intent intent) {
        final PackageManager pm = getPackageManager();
        final List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo info : resolveInfos) {
            if (GOOGLE_SETUPWIZARD_PACKAGE.equals(info.activityInfo.packageName)) {
                final ComponentName componentName = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                pm.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        }
        pm.setComponentEnabledSetting(getComponentName(), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    public void launchGoogleAccountSetup() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(CMAccount.EXTRA_FIRST_RUN, true);
        bundle.putBoolean(CMAccount.EXTRA_ALLOW_SKIP, true);
        AccountManager.get(this).addAccount(CMAccount.ACCOUNT_TYPE_GOOGLE, null, null, bundle, this, new AccountManagerCallback<Bundle>() {
            @Override
            public void run(AccountManagerFuture<Bundle> bundleAccountManagerFuture) {
                if (isDestroyed()) return; //There is a change this activity has been torn down.
                Page page = mPageList.findPage(R.string.setup_google_account);
                if (page != null) {
                    onPageFinished(page);
                }
            }
        }, null);
    }

    private void finishSetup() {
        Settings.Global.putInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);
        ((CMAccount)AppGlobals.getInitialApplication()).enableStatusBar();
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory("android.intent.category.HOME");
        disableSetupWizards(intent);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | intent.getFlags());
        startActivity(intent);
        finish();
    }

    private boolean accountExists(String accountType) {
        return AccountManager.get(this).getAccountsByType(accountType).length > 0;
    }

    private class CMPagerAdapter extends FragmentStatePagerAdapter {

        private int mCutOffPage;

        private CMPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int i) {
            return mPageList.get(i).createFragment();
        }

        @Override
        public int getItemPosition(Object object) {
            return POSITION_NONE;
        }

        @Override
        public int getCount() {
            if (mPageList == null)
                return 0;
            return Math.min(mCutOffPage, mPageList.size());
        }

        public void setCutOffPage(int cutOffPage) {
            if (cutOffPage < 0) {
                cutOffPage = Integer.MAX_VALUE;
            }
            mCutOffPage = cutOffPage;
        }

        public int getCutOffPage() {
            return mCutOffPage;
        }
    }



    public static class DepthPageTransformer implements ViewPager.PageTransformer {
        private static float MIN_SCALE = 0.5f;

        public void transformPage(View view, float position) {
            int pageWidth = view.getWidth();

            if (position < -1) {
                view.setAlpha(0);

            } else if (position <= 0) { // [-1,0]
                // Use the default slide transition when moving to the left page
                view.setAlpha(1);
                view.setTranslationX(0);
                view.setScaleX(1);
                view.setScaleY(1);

            } else if (position <= 1) { // (0,1]
                // Fade the page out.
                view.setAlpha(1 - position);

                // Counteract the default slide transition
                view.setTranslationX(pageWidth * -position);

                // Scale the page down (between MIN_SCALE and 1)
                float scaleFactor = MIN_SCALE
                        + (1 - MIN_SCALE) * (1 - Math.abs(position));
                view.setScaleX(scaleFactor);
                view.setScaleY(scaleFactor);

            } else { // (1,+Infinity]
                // This page is way off-screen to the right.
                view.setAlpha(0);
            }
        }
    }
}
