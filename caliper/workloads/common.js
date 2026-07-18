'use strict';

const { WorkloadModuleBase } = require('@hyperledger/caliper-core');

class HttpApiWorkload extends WorkloadModuleBase {
    constructor() {
        super();
        this.baseUrl = process.env.RPKI_BASE_URL || 'http://127.0.0.1:8082/api';
    }

    async callApi(path, method = 'GET', body) {
        const init = {
            method,
            headers: { 'Content-Type': 'application/json' }
        };
        if (body !== undefined) {
            init.body = JSON.stringify(body);
        }
        const resp = await fetch(`${this.baseUrl}${path}`, init);
        if (!resp.ok) {
            const txt = await resp.text();
            throw new Error(`HTTP ${resp.status}: ${txt}`);
        }
        return resp.text();
    }
}

module.exports = {
    HttpApiWorkload
};
