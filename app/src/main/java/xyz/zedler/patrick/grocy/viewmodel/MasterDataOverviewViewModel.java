package xyz.zedler.patrick.grocy.viewmodel;

/*
    This file is part of Grocy Android.

    Grocy Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Grocy Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Grocy Android.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2020 by Patrick Zedler & Dominic Zedler
*/

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import com.android.volley.VolleyError;

import java.util.ArrayList;

import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.InfoFullscreen;
import xyz.zedler.patrick.grocy.model.Location;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.ProductGroup;
import xyz.zedler.patrick.grocy.model.QuantityUnit;
import xyz.zedler.patrick.grocy.model.SnackbarMessage;
import xyz.zedler.patrick.grocy.model.Store;
import xyz.zedler.patrick.grocy.repository.MasterDataOverviewRepository;
import xyz.zedler.patrick.grocy.util.Constants;

public class MasterDataOverviewViewModel extends AndroidViewModel {

    private static final String TAG = MasterDataOverviewViewModel.class.getSimpleName();

    private final SharedPreferences sharedPrefs;
    private final DownloadHelper dlHelper;
    private final GrocyApi grocyApi;
    private final EventHandler eventHandler;
    private final MasterDataOverviewRepository repository;

    private final MutableLiveData<Boolean> isLoadingLive;
    private final MutableLiveData<InfoFullscreen> infoFullscreenLive;
    private final MutableLiveData<Boolean> offlineLive;

    private MutableLiveData<ArrayList<Store>> storesLive;
    private MutableLiveData<ArrayList<Location>> locationsLive;
    private MutableLiveData<ArrayList<ProductGroup>> productGroupsLive;
    private MutableLiveData<ArrayList<QuantityUnit>> quantityUnitsLive;
    private MutableLiveData<ArrayList<Product>> productsLive;

    private DownloadHelper.Queue currentQueueLoading;
    private final boolean debug;

    public MasterDataOverviewViewModel(@NonNull Application application) {
        super(application);

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
        debug = sharedPrefs.getBoolean(Constants.PREF.DEBUG, false);

        isLoadingLive = new MutableLiveData<>(false);
        dlHelper = new DownloadHelper(getApplication(), TAG, isLoadingLive::setValue);
        grocyApi = new GrocyApi(getApplication());
        eventHandler = new EventHandler();
        repository = new MasterDataOverviewRepository(application);

        infoFullscreenLive = new MutableLiveData<>();
        offlineLive = new MutableLiveData<>(false);
        storesLive = new MutableLiveData<>();
        locationsLive = new MutableLiveData<>();
        productGroupsLive = new MutableLiveData<>();
        quantityUnitsLive = new MutableLiveData<>();
        productsLive = new MutableLiveData<>();
    }

    public void loadFromDatabase(boolean downloadAfterLoading) {
        repository.loadFromDatabase(
                (stores, locations, productGroups, quantityUnits, products) -> {
                    this.storesLive.setValue(stores);
                    this.locationsLive.setValue(locations);
                    this.productGroupsLive.setValue(productGroups);
                    this.quantityUnitsLive.setValue(quantityUnits);
                    this.productsLive.setValue(products);
                    if(downloadAfterLoading) downloadData();
                }
        );
    }

    private String getLastTime(String sharedPref) {
        return sharedPrefs.getString(sharedPref, null);
    }

    public void downloadData(@Nullable String dbChangedTime) {
        if(currentQueueLoading != null) {
            currentQueueLoading.reset(true);
            currentQueueLoading = null;
        }
        if(isOffline()) { // skip downloading and update recyclerview
            isLoadingLive.setValue(false);
            //updateFilteredShoppingListItems(); todo?
            return;
        }
        if(dbChangedTime == null) {
            dlHelper.getTimeDbChanged(
                    (DownloadHelper.OnStringResponseListener) this::downloadData,
                    () -> onDownloadError(null)
            );
            return;
        }

        // get last offline db-changed-time values
        String lastTimeStores = getLastTime(Constants.PREF.DB_LAST_TIME_STORES);
        String lastTimeLocations = getLastTime(Constants.PREF.DB_LAST_TIME_LOCATIONS);
        String lastTimeProductGroups = getLastTime(Constants.PREF.DB_LAST_TIME_PRODUCT_GROUPS);
        String lastTimeQuantityUnits = getLastTime(Constants.PREF.DB_LAST_TIME_QUANTITY_UNITS);
        String lastTimeProducts = getLastTime(Constants.PREF.DB_LAST_TIME_PRODUCTS);

        SharedPreferences.Editor editPrefs = sharedPrefs.edit();
        DownloadHelper.Queue queue = dlHelper.newQueue(this::onQueueEmpty, this::onDownloadError);
        if(lastTimeStores == null || !lastTimeStores.equals(dbChangedTime)) {
            queue.append(dlHelper.getStores(stores -> {
                //this.storesLive. = stores;
                editPrefs.putString(Constants.PREF.DB_LAST_TIME_STORES, dbChangedTime);
                editPrefs.apply();
            }));
        } else if(debug) Log.i(TAG, "downloadData: skipped Stores download");
        if(lastTimeLocations == null || !lastTimeLocations.equals(dbChangedTime)) {
            queue.append(dlHelper.getLocations(locations -> {
                this.locationsLive.setValue(locations);
                editPrefs.putString(Constants.PREF.DB_LAST_TIME_LOCATIONS, dbChangedTime);
                editPrefs.apply();
            }));
        } else if(debug) Log.i(TAG, "downloadData: skipped Locations download");
        if(lastTimeProductGroups == null || !lastTimeProductGroups.equals(dbChangedTime)) {
            queue.append(dlHelper.getProductGroups(productGroups -> {
                this.productGroupsLive.setValue(productGroups);
                editPrefs.putString(Constants.PREF.DB_LAST_TIME_PRODUCT_GROUPS, dbChangedTime);
                editPrefs.apply();
            }));
        } else if(debug) Log.i(TAG, "downloadData: skipped ProductGroups download");
        if(lastTimeQuantityUnits == null || !lastTimeQuantityUnits.equals(dbChangedTime)) {
            queue.append(dlHelper.getQuantityUnits(quantityUnits -> {
                this.quantityUnitsLive.setValue(quantityUnits);
                editPrefs.putString(Constants.PREF.DB_LAST_TIME_QUANTITY_UNITS, dbChangedTime);
                editPrefs.apply();
            }));
        } else if(debug) Log.i(TAG, "downloadData: skipped QuantityUnits download");
        if(lastTimeProducts == null || !lastTimeProducts.equals(dbChangedTime)) {
            queue.append(dlHelper.getProducts(products -> {
                this.productsLive.setValue(products);
                editPrefs.putString(Constants.PREF.DB_LAST_TIME_PRODUCTS, dbChangedTime);
                editPrefs.apply();
            }));
        } else if(debug) Log.i(TAG, "downloadData: skipped Products download");

        if(queue.isEmpty()) return;

        currentQueueLoading = queue;
        queue.start();
    }

    public void downloadData() {
        downloadData(null);
    }

    private void onQueueEmpty() {
        if(isOffline()) setOfflineLive(false);
        repository.updateDatabase(
                this.storesLive.getValue(),
                this.locationsLive.getValue(),
                this.productGroupsLive.getValue(),
                this.quantityUnitsLive.getValue(),
                this.productsLive.getValue(),
                () -> {}
        );
    }

    private void onDownloadError(@Nullable VolleyError error) {
        if(debug) Log.e(TAG, "onError: VolleyError: " + error);
        showMessage(getString(R.string.msg_no_connection));
        if(!isOffline()) setOfflineLive(true);
    }

    @NonNull
    public MutableLiveData<Boolean> getOfflineLive() {
        return offlineLive;
    }

    public Boolean isOffline() {
        return offlineLive.getValue();
    }

    public void setOfflineLive(boolean isOffline) {
        offlineLive.setValue(isOffline);
    }

    @NonNull
    public MutableLiveData<Boolean> getIsLoadingLive() {
        return isLoadingLive;
    }

    @NonNull
    public MutableLiveData<InfoFullscreen> getInfoFullscreenLive() {
        return infoFullscreenLive;
    }

    @NonNull
    public MutableLiveData<ArrayList<Store>> getStoresLive() {
        return storesLive;
    }

    public MutableLiveData<ArrayList<Location>> getLocationsLive() {
        return locationsLive;
    }

    public MutableLiveData<ArrayList<ProductGroup>> getProductGroupsLive() {
        return productGroupsLive;
    }

    public MutableLiveData<ArrayList<QuantityUnit>> getQuantityUnitsLive() {
        return quantityUnitsLive;
    }

    public MutableLiveData<ArrayList<Product>> getProductsLive() {
        return productsLive;
    }

    public void setCurrentQueueLoading(DownloadHelper.Queue queueLoading) {
        currentQueueLoading = queueLoading;
    }

    private void showErrorMessage() {
        showMessage(getString(R.string.error_undefined));
    }

    private void showMessage(@NonNull String message) {
        showSnackbar(new SnackbarMessage(message));
    }

    private void showSnackbar(@NonNull SnackbarMessage snackbarMessage) {
        eventHandler.setValue(snackbarMessage);
    }

    private void sendEvent(int type) {
        eventHandler.setValue(new Event() {
            @Override
            public int getType() {return type;}
        });
    }

    private void sendEvent(int type, Bundle bundle) {
        eventHandler.setValue(new Event() {
            @Override
            public int getType() {return type;}

            @Override
            public Bundle getBundle() {return bundle;}
        });
    }

    @NonNull
    public EventHandler getEventHandler() {
        return eventHandler;
    }

    public boolean isFeatureEnabled(String pref) {
        if(pref == null) return true;
        return sharedPrefs.getBoolean(pref, true);
    }

    private String getString(@StringRes int resId) {
        return getApplication().getString(resId);
    }

    private String getString(@StringRes int resId, Object... formatArgs) {
        return getApplication().getString(resId, formatArgs);
    }

    @Override
    protected void onCleared() {
        dlHelper.destroy();
        super.onCleared();
    }
}