package com.exe.morphia3d.feature;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.features")
public class FeatureGateProperties {
    private Toggle ai = new Toggle();
    private Toggle quote = new Toggle();
    private Toggle cart = new Toggle();
    private Toggle order = new Toggle();
    private Toggle checkout = new Toggle();
    private Toggle payment = new Toggle();
    private Toggle adminProductSnapshotUpload = new Toggle();

    public boolean isEnabled(FeatureFamily family) {
        Toggle toggle = switch (family) {
            case AI -> ai;
            case QUOTE -> quote;
            case CART -> cart;
            case ORDER -> order;
            case CHECKOUT -> checkout;
            case PAYMENT -> payment;
        };
        return toggle != null && toggle.isEnabled();
    }

    public boolean anyEnabled() {
        for (FeatureFamily family : FeatureFamily.values()) {
            if (isEnabled(family)) return true;
        }
        return false;
    }

    public boolean isAdminProductSnapshotUploadEnabled() {
        return adminProductSnapshotUpload != null && adminProductSnapshotUpload.isEnabled();
    }

    public Toggle getAi() { return ai; }
    public void setAi(Toggle ai) { this.ai = ai; }
    public Toggle getQuote() { return quote; }
    public void setQuote(Toggle quote) { this.quote = quote; }
    public Toggle getCart() { return cart; }
    public void setCart(Toggle cart) { this.cart = cart; }
    public Toggle getOrder() { return order; }
    public void setOrder(Toggle order) { this.order = order; }
    public Toggle getCheckout() { return checkout; }
    public void setCheckout(Toggle checkout) { this.checkout = checkout; }
    public Toggle getPayment() { return payment; }
    public void setPayment(Toggle payment) { this.payment = payment; }
    public Toggle getAdminProductSnapshotUpload() { return adminProductSnapshotUpload; }
    public void setAdminProductSnapshotUpload(Toggle adminProductSnapshotUpload) {
        this.adminProductSnapshotUpload = adminProductSnapshotUpload;
    }

    public static class Toggle {
        private boolean enabled;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
