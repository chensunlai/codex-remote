'use strict';
require('node:http').setGlobalProxyFromEnv?.();
require('./service.cjs').runMaintenance(process.argv[2]).catch(error=>{console.error(error.message);process.exitCode=1;});
