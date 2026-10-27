package dev.ryuya.homepodbridge;

/** Independent local TX evidence. No conversion of queue bytes/RTT to one-way audio delay. */
record NetworkTiming(long[] values, int wifiRequest) {
  long value(int index) {
    return values != null && values.length == 17 && index >= 0 && index < 17 ? values[index] : -1;
  }

  long transit(int index) {
    return index >= 6 && index <= 8 && value(5) >= 5 ? value(index) : -1;
  }

  int priorityResource() {
    if (value(9) < 0) return R.string.network_unknown;
    if (value(9) == 0) return R.string.network_normal;
    return (value(10) & 0xfc) == 184 && value(11) == 6
        ? R.string.network_priority_accepted
        : R.string.network_priority_unavailable;
  }

  int wifiResource() {
    if (value(9) == 0) return R.string.network_wifi_off;
    if (wifiRequest == 2) return R.string.network_wifi_eligible;
    if (wifiRequest == 1) return R.string.network_wifi_waiting;
    if (wifiRequest == 3) return R.string.network_wifi_not_wifi;
    if (wifiRequest == 4) return R.string.network_wifi_denied;
    return R.string.network_wifi_unavailable;
  }
}
