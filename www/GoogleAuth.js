var exec = require('cordova/exec');

exports.login = function (options, success, error) {
    options = options || {};
    exec(success, error, 'GoogleAuth', 'login', [options]);
};
