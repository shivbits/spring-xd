/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * RequireJS configuration and bootstrapping angular.
 *
 * @author Ilayaperumal Gopinathan
 */
require.config({
  paths: {
    domReady: '../lib/requirejs-domready/domReady',
    angular: '../lib/angular/angular',
    jquery: '../lib/jquery/jquery',
    bootstrap: '../lib/bootstrap/bootstrap',
    ngResource: '../lib/angular-resource/angular-resource',
    ngRoute: '../lib/angular-route/angular-route',
    angularHighlightjs: '../lib/angular-highlightjs/angular-highlightjs',
    highlightjs: '../lib/highlightjs/highlight.pack',
    uiRouter: '../lib/angular-ui-router/angular-ui-router',
    cgBusy: '../lib/angular-busy/angular-busy',
    ngGrowl: '../lib/angular-growl/angular-growl',
    angularMocks: '../lib/angular-mocks/angular-mocks'
  },
  shim: {
    angular: {
      exports: 'angular'
    },
    bootstrap: {
      deps: ['jquery']
    },
    'uiRouter': {
      deps: ['angular']
    },
    cgBusy: {
      deps: ['angular']
    },
    'angularHighlightjs': {
      deps: ['angular', 'highlightjs']
    },
    'ngResource': {
      deps: ['angular']
    },
    'ngGrowl': {
      deps: ['angular']
    },
    'angularMocks': {
      deps: ['angular'],
      'exports': 'angular.mock'
    }
  }
});

define([
  'require',
  'angular',
  'app',
  'routes'
], function (require, angular) {
  'use strict';

  require(['domReady!'], function (document) {
    angular.bootstrap(document, ['xdAdmin']);
  });
  require(['jquery', 'bootstrap'], function() {
    console.log('Loaded Bootstrap.');
    updateGrowl();
    $(window).on('scroll resize', function() {
      updateGrowl();
    });
    return {};
  });

  function updateGrowl() {
    var bodyScrollTop = $('body').scrollTop();
    var navHeight = $('nav').outerHeight();

    if (bodyScrollTop > navHeight) {
      $('.growl').css('top', 10);
    } else if (bodyScrollTop >= 0) {
      var distance = navHeight - bodyScrollTop;
      $('.growl').css('top', distance + 10);
    }
  }
});
