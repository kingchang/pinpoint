package com.navercorp.pinpoint.collector.receiver.grpc.cache;

import com.navercorp.pinpoint.common.server.vo.ApplicationUid;
import com.navercorp.pinpoint.common.server.vo.ServiceUid;

public interface UidCache {

    ServiceUid getServiceUid(String serviceName);

    void put(String serviceName, ServiceUid serviceUid);

    // ----------

    ApplicationUid getApplicationUid(ServiceUid serviceUid, String applicationName);

    void put(ServiceUid serviceUid, String applicationName, ApplicationUid applicationUid);
}
