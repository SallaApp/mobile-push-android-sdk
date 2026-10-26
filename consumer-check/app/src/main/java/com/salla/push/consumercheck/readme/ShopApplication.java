package com.salla.push.consumercheck.readme;

import android.app.Application;

import com.salla.push.SallaPush;
import com.salla.push.SallaPushConfig;
import com.salla.push.SallaPushLogLevel;
import com.salla.push.SallaPushNotification;

/** docs/REFERENCE.md's Java snippets; the app supplies the abstract methods (its navigation and screens). */
public abstract class ShopApplication extends Application {

    abstract void open(SallaPushNotification notification);

    abstract void showPermission(boolean granted);

    abstract void showCustomer(boolean signedIn);

    @Override
    public void onCreate() {
        super.onCreate();
        // snippet:java-application
        SallaPushConfig config = new SallaPushConfig.Builder()
                .setLogLevel(SallaPushLogLevel.INFO)
                .build();
        SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002", config);
        // end-snippet
    }

    void useSallaPush() {
        // snippet:java-calls
        SallaPush.getUser().addTag("add_to_cart", String.valueOf(System.currentTimeMillis()));
        SallaPush.getUser().addObserver(state -> showCustomer(state.getCurrent().isLoggedIn()));
        SallaPush.getNotifications().addClickListener(event -> open(event.getNotification()));
        SallaPush.getNotifications().requestPermission(true, granted -> showPermission(granted));
        // end-snippet
    }
}
