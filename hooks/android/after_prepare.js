'use strict';

var fs = require('fs');
var path = require('path');

module.exports = function (context) {
    if (!context.opts.platforms || context.opts.platforms.indexOf('android') === -1) {
        return;
    }

    var buildGradle = path.join(context.opts.projectRoot, 'platforms', 'android', 'app', 'build.gradle');
    if (!fs.existsSync(buildGradle)) {
        return;
    }

    var source = fs.readFileSync(buildGradle, 'utf8');
    var updated = source.replace(/^\s*apply plugin:\s*['"]kotlin-android-extensions['"]\s*\r?\n/m, '');
    if (updated !== source) {
        fs.writeFileSync(buildGradle, updated, 'utf8');
        console.log('[cordova-plugin-google-auth] Removed obsolete kotlin-android-extensions plugin.');
    }
};
