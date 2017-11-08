package cn.gooong.android.amap;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.Toast;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.autonavi.amap.mapcore.Inner_3dMap_location;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.Vector;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends Activity {
    //HandleMessage
    private static final int REPORT_POS_SUCCESS = 1;
    private static final int REPORT_POS_FAILED = 2;
    private static final int FETCH_ALL_POS_SUCCESS = 3;
    private static final int FETCH_ALL_POS_FAILED = 4;
    private static final int DELETE_POS_SUCCESS = 5;
    private static final int DELETE_POS_FAILED = 6;
    private static final int USER_FETCH_ALL_POS_SUCCESS = 7;
    private static final int USER_FETCH_ALL_POS_FAILED = 8;
    //网络错误和定位错误计数
    private static int sLocateErrorCount = 0;
    private static int sNetworkErrorCount = 0;
    private static int sSamePosTime = 0;
    //双击退出时间记录
    private long mFirstTime = 0;
    //组件
    private MapView mMapView;
    private AMap mMap;
    //位置和Marker相关
    private Marker mShowingMarker;
    private Inner_3dMap_location mCurrentLocation = null;  //用户当前位置
    private Hashtable<String, JsonObject> mAllLocations = new Hashtable<>(); //存储当前在线的所有用户(除了自己)
    private final Hashtable<String, Marker> mAllMarkers = new Hashtable<>(); //存储所有的Marker
    private final Vector<String> mIdsNeedDelete = new Vector<>();    //需要删除的MarkerID
    private final Vector<String> mIdsNeedRefresh = new Vector<>(); //需要更新的MarkerID
    private final Vector<String> mIdsNeedAdd = new Vector<>(); //需要加入进来的MarkerID

    //线程
    private Timer mTimer;
    private Handler mHandler;
    //用户ID
    private String mUserId;
    private ObjectAnimator mObjectAnimator;
    private final OkHttpClient mOkHttpClient = new OkHttpClient();
    private static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mHandler = new Handler();    //异步处理
        //获取地图控件引用
        mMapView = findViewById(R.id.map);
        mMapView.onCreate(savedInstanceState);
        initMapViewSetting();
        initUI();
        //获取ID
        mUserId = getmUserId();
        //启动定时获取获取任务
        mTimer = new Timer();
        mTimer.schedule(fetchAllPosTask,
                getResources().getInteger(R.integer.fetch_all_delay_ms),
                getResources().getInteger(R.integer.fetch_all_period_ms));
    }

    //初始化地图设置
    private void initMapViewSetting() {
        mMap = mMapView.getMap();
        //设置UI和操作手势
        mMap.getUiSettings().setMyLocationButtonEnabled(false);//设置默认定位按钮是否显示。
        mMap.getUiSettings().setZoomControlsEnabled(false);
        mMap.getUiSettings().setRotateGesturesEnabled(false);
        mMap.getUiSettings().setTiltGesturesEnabled(false);
        mMap.setMapCustomEnable(true);//true 开启; false 关闭
        //设置定位蓝点
        MyLocationStyle myLocationStyle;
        myLocationStyle = new MyLocationStyle();//初始化定位蓝点样式类myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE);//连续定位、且将视角移动到地图中心点，定位点依照设备方向旋转，并且会跟随设备移动。（1秒1次定位）如果不设置myLocationType，默认也会执行此种模式。
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_FOLLOW_NO_CENTER);//连续定位、且将视角移动到地图中心点，定位蓝点跟随设备移动。（1秒1次定位）
        myLocationStyle.showMyLocation(true);
        myLocationStyle.interval(getResources().getInteger(R.integer.locate_myself_interval_ms));
        mMap.setMyLocationStyle(myLocationStyle);//设置定位蓝点的Style
        mMap.setMyLocationEnabled(true);// 设置为true表示启动显示定位蓝点，false表示隐藏定位蓝点并不进行定位，默认是false。

        //绑定位置改变事件
        mMap.setOnMyLocationChangeListener(myLocationChangeListener);
        //绑定 Marker 被点击事件
        mMap.setOnMarkerClickListener(markerClickListener);
        mMap.setOnMapClickListener(new AMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng) {
                if (mShowingMarker != null) {
                    mShowingMarker.hideInfoWindow();
                    mShowingMarker = null;
                }
            }
        });
    }

    //初始化UI
    private void initUI() {
        //按钮事件和字体
        Typeface typeface = Typeface.createFromAsset(getAssets(), "font/iconfont.ttf");
        Button mRefreshBtn = findViewById(R.id.refresh_btn);
        mRefreshBtn.setOnClickListener(refreshClickListener);
        mRefreshBtn.setTypeface(typeface);
        Button mLocateBtn = findViewById(R.id.locate_btn);
        mLocateBtn.setOnClickListener(locateClickListener);
        mLocateBtn.setTypeface(typeface);
        //刷新按钮动画
        mObjectAnimator = ObjectAnimator.ofFloat(mRefreshBtn, "rotation", 0f, 360.0f);
        mObjectAnimator.setDuration(600);
        mObjectAnimator.setInterpolator(new LinearInterpolator());//不停顿
        mObjectAnimator.setRepeatCount(getResources().getInteger(R.integer.refresh_btn_rotate_times));//设置动画重复次数
        mObjectAnimator.setRepeatMode(ValueAnimator.RESTART);//动画重复模式

    }

    //获取或生成用户ID
    private String getmUserId() {
        SharedPreferences sharedPreferences = getSharedPreferences(
                getString(R.string.shared_preferences_user_group), MODE_PRIVATE);
        if (!sharedPreferences.contains(getString(R.string.user_id_key))) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            String userId = UUID.randomUUID().toString();
            editor.putString(getString(R.string.user_id_key), userId);
            editor.apply();
            return userId;
        }
        else{
            return sharedPreferences.getString(getString(R.string.user_id_key), "");
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        //在activity执行onDestroy时执行mMapView.onDestroy()，销毁地图
        mMapView.onDestroy();
        System.exit(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        //在activity执行onResume时执行mMapView.onResume ()，重新绘制加载地图
        mMapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //在activity执行onPause时执行mMapView.onPause ()，暂停地图的绘制
        mMapView.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        //在activity执行onSaveInstanceState时执行mMapView.onSaveInstanceState (outState)，保存地图当前的状态
        mMapView.onSaveInstanceState(outState);
    }

    @Override
    public void finish() {
        deletePosition();
        mTimer.cancel();
        super.finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            long secondTime = System.currentTimeMillis();
            if (secondTime - mFirstTime > 2000) {
                Toast.makeText(this, getString(R.string.exit_program), Toast.LENGTH_SHORT).show();
                mFirstTime = secondTime;
                return true;
            } else {
                finish();
            }
        }
        return super.onKeyDown(keyCode, event);
    }


    private MarkerOptions getMarkerOptions(JsonObject json) {
        Double longitude = json.get("lon").getAsDouble();
        Double latitude = json.get("lat").getAsDouble();
        return new MarkerOptions().position(new LatLng(latitude, longitude)).
                snippet(json.get("address").getAsString()).title(json.get("city").getAsString());
        //{"poiid": "", "lon": 116.307819, "receive_time": 1509678152.4225507, "errorInfo": "success", "isOffset": true, "time": 1509678151616, "provider": "lbs", "locationDetail": "#csid:1c41325cbb8249fd9863c782295249df", "bearing": 0, "adcode": "110108", "locationType": 5, "errorCode": 0, "street": "", "accuracy": 15, "province": "\u5317\u4eac\u5e02", "altitude": 0, "address": "\u5317\u4eac\u5e02\u6d77\u6dc0\u533a\u6d77\u6dc0\u8def131\u53f7\u9760\u8fd1\u4e2d\u56fd\u94f6\u884c(\u5317\u5927\u652f\u884c)", "road": "\u6d77\u6dc0\u8def", "country": "\u4e2d\u56fd", "desc": "", "citycode": "010", "speed": 0, "poiname": "", "number": "", "district": "\u6d77\u6dc0\u533a", "city": "\u5317\u4eac\u5e02", "lat": 39.986987, "floor": "", "aoiname": ""}
    }


    private void handleMessage(int what) {
        switch (what) {
            case REPORT_POS_SUCCESS: //报告位置成功
                break;

            case REPORT_POS_FAILED: //报告位置失败
                onNetworkError();
                break;

            case FETCH_ALL_POS_SUCCESS:    //获取所有位置成功
                Marker tmpMarker;
                String tmpId;
                JsonObject tmpJson;
                MarkerOptions tmpMarkerOptions;
                while (!mIdsNeedDelete.isEmpty()) {
                    tmpId = mIdsNeedDelete.remove(0);
                    tmpMarker = mAllMarkers.get(tmpId);
                    tmpMarker.remove();
                    mAllMarkers.remove(tmpId);
                    tmpMarker.destroy();
                }
                while (!mIdsNeedRefresh.isEmpty()) {
                    tmpId = mIdsNeedRefresh.remove(0);
                    tmpMarker = mAllMarkers.get(tmpId);
                    tmpJson = mAllLocations.get(tmpId);
                    tmpMarkerOptions = getMarkerOptions(tmpJson);
                    tmpMarker.setMarkerOptions(tmpMarkerOptions);
                }
                while (!mIdsNeedAdd.isEmpty()) {
                    tmpId = mIdsNeedAdd.remove(0);
                    tmpJson = mAllLocations.get(tmpId);
                    tmpMarkerOptions = getMarkerOptions(tmpJson);
                    tmpMarker = mMap.addMarker(tmpMarkerOptions);
                    mAllMarkers.put(tmpId, tmpMarker);
                }
                break;

            case FETCH_ALL_POS_FAILED:    //获取所有位置失败
                onNetworkError();
                break;

            case DELETE_POS_SUCCESS:
                break;

            case DELETE_POS_FAILED:
                break;

            case USER_FETCH_ALL_POS_SUCCESS:
                Toast.makeText(this,
                        "刷新成功，当前" + (mAllLocations.size() + 1) + "人在线", Toast.LENGTH_SHORT).show();
                break;

            case USER_FETCH_ALL_POS_FAILED:
                Toast.makeText(this,
                        "刷新失败，无法连接至服务器", Toast.LENGTH_SHORT).show();
                break;

            default:
                break;
        }
    }


    //获取当前所有位置
    private final TimerTask fetchAllPosTask = new TimerTask() {
        @Override
        public void run() {
            fetchAllPos(0);
        }
    };

    private void fetchAllPos(int flag) {
        try {
            String url = getString(R.string.net_addr) + getString(R.string.get_all_route);
            Request request = new Request.Builder()
                    .url(url)
                    .build();
            Response response = mOkHttpClient.newCall(request).execute();
            @SuppressWarnings("ConstantConditions") String jsonString = response.body().string();
            JsonParser parser = new JsonParser();
            JsonObject json = (JsonObject) parser.parse(jsonString);

            Hashtable<String, JsonObject> newLocations = new Hashtable<>();
            for (String tmp_id : json.keySet()) {
                if (!tmp_id.equals(mUserId)) {  //排掉自己
                    JsonObject tmp_location = json.getAsJsonObject(tmp_id);
                    newLocations.put(tmp_id, tmp_location);
                    if (mAllLocations.containsKey(tmp_id)) {
                        if (!tmp_location.equals(mAllLocations.get(tmp_id))) {
                            //同一个ID的两个位置变了，应该移动Marker
                            mIdsNeedRefresh.add(tmp_id);
                        }
                    } else {
                        //这个ID应该绘制新的Marker
                        mIdsNeedAdd.add(tmp_id);
                    }
                }
            }
            Set<String> tmpSet = mAllLocations.keySet();
            tmpSet.removeAll(newLocations.keySet());
            mIdsNeedDelete.addAll(tmpSet);  //需要删除的MarkerID

            mAllLocations = newLocations;
            //mHandler.sendEmptyMessage(FETCH_ALL_POS_SUCCESS);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleMessage(FETCH_ALL_POS_SUCCESS);
                }
            });
            if (flag != 0) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleMessage(USER_FETCH_ALL_POS_SUCCESS);
                    }
                });
            }
        } catch (IOException e) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleMessage(FETCH_ALL_POS_FAILED);
                }
            });
            e.printStackTrace();
            if (flag != 0) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleMessage(USER_FETCH_ALL_POS_FAILED);
                    }
                });
            }
        }
    }

    //报告位置
    private void reportPosition() {
        String json = mCurrentLocation.toStr();
        //mCurrentLocation
        Log.i("reportPos", json);
        RequestBody body = RequestBody.create(JSON, json);
        String url = getString(R.string.net_addr) + getString(R.string.refresh_route) + mUserId;
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        Call call = mOkHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleMessage(REPORT_POS_FAILED);
                    }
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleMessage(REPORT_POS_SUCCESS);
                    }
                });
            }
        });
    }

    //删除当前位置
    private void deletePosition() {
        String url = getString(R.string.net_addr) + getString(R.string.delete_route) + mUserId;
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();
        Call call = mOkHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleMessage(DELETE_POS_FAILED);
                    }
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleMessage(DELETE_POS_SUCCESS);
                    }
                });
            }
        });
    }

    // 定义 Marker 点击事件监听
    private final AMap.OnMarkerClickListener markerClickListener = new AMap.OnMarkerClickListener() {
        // marker 对象被点击时回调的接口
        // 返回 true 则表示接口已响应事件，否则返回false
        @Override
        public boolean onMarkerClick(Marker marker) {
            if (marker.isInfoWindowShown()) {
                marker.hideInfoWindow();
                mShowingMarker = null;
            } else {
                marker.showInfoWindow();
                mShowingMarker = marker;
            }
            return true;
        }
    };

    private final AMap.OnMyLocationChangeListener myLocationChangeListener = new AMap.OnMyLocationChangeListener() {
        public void onMyLocationChange(final Location location) {
            if (null != location) {
                Log.i("myLocationChange", location.toString());
                Inner_3dMap_location tmpLocation = (Inner_3dMap_location) location;
                //在一定时间内若位置一样，则不更新
                if(mCurrentLocation!=null && tmpLocation.toStr().equals(mCurrentLocation.toStr())){
                    if(sSamePosTime*getResources().getInteger(R.integer.locate_myself_interval_ms) <= getResources().getInteger(R.integer.max_expire_time_ms)){
                        sSamePosTime+=1;
                        return;
                    }
                }
                sSamePosTime = 0;
                mCurrentLocation = tmpLocation;
                if (mCurrentLocation.getErrorCode() == 0) {
                    reportPosition();   //异步报告当前位置
                } else {
                    onLocateError(mCurrentLocation);//定位失败
                }
            }
        }
    };

    private void onLocateError(Inner_3dMap_location location) {
        Log.i("locate_error", location.toStr());
        sLocateErrorCount += 1;
        if (sLocateErrorCount % 50 == 2) {
            Toast.makeText(this, getString(R.string.locate_error), Toast.LENGTH_SHORT).show();
        }
    }

    private void onNetworkError() {
        sNetworkErrorCount += 1;
        if (sNetworkErrorCount % 50 == 2) {
            Toast.makeText(this, getString(R.string.network_error), Toast.LENGTH_SHORT).show();
        }
    }

    private final View.OnClickListener refreshClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
        if (!mObjectAnimator.isRunning()) {
            mObjectAnimator.end();
            mObjectAnimator.start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    fetchAllPos(1);
                }
            }).start();
        }
        }
    };

    private final View.OnClickListener locateClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
        if (mCurrentLocation != null && mCurrentLocation.getErrorCode() == 0) {
            LatLng tmpLatLng = new LatLng(mCurrentLocation.getLatitude(), mCurrentLocation.getLongitude());
            mMap.animateCamera(CameraUpdateFactory.newLatLng(tmpLatLng));
        }
        }
    };
}
