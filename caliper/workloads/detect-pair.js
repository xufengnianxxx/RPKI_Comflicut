'use strict';

const { HttpApiWorkload } = require('./common');

class DetectPairWorkload extends HttpApiWorkload {
    async submitTransaction() {
        const certResp = await this.callApi('/cert/certs?page=1&size=2');
        const parsed = JSON.parse(certResp);
        const records = parsed?.data?.records || [];
        if (records.length < 2) {
            return;
        }
        const certIdA = records[0].id;
        const certIdB = records[1].id;
        await this.callApi('/cert/detect-pair-persist', 'POST', { certIdA, certIdB });
    }
}

function createWorkloadModule() {
    return new DetectPairWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;
