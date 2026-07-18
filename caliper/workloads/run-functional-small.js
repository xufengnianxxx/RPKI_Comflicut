'use strict';

const { HttpApiWorkload } = require('./common');

class RunFunctionalSmallWorkload extends HttpApiWorkload {
    async submitTransaction() {
        await this.callApi('/test/functional', 'POST', {});
    }
}

function createWorkloadModule() {
    return new RunFunctionalSmallWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;
