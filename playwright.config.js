import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests',timeout:30000,use:{baseURL:'http://127.0.0.1:8899'},webServer:{command:'lein trampoline run -m clojure.main scripts/qa-server.clj',url:'http://127.0.0.1:8899/',timeout:180000,reuseExistingServer:!process.env.CI}});
