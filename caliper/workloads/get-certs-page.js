'use strict';

const { HttpApiWorkload } = require('./common');

class GetCertsPageWorkload extends HttpApiWorkload {
    async submitTransaction() {
        const page = 1 + Math.floor(Math.random() * 3);
        await this.callApi(`/cert/certs?page=${page}&size=15`);
    }
}

function createWorkloadModule() {
    return new GetCertsPageWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;
