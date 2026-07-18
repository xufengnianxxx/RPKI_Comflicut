'use strict';

const { HttpApiWorkload } = require('./common');

class QueryFunctionalReportWorkload extends HttpApiWorkload {
    async submitTransaction() {
        await this.callApi('/test/functional/report');
    }
}

function createWorkloadModule() {
    return new QueryFunctionalReportWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;
