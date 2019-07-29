/**
 * Copyright 2009-2011 The Regents of the University of California Licensed
 * under the Educational Community License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
/*jslint browser: true, nomen: true*/
/*global define*/
define(["jquery", "underscore", "backbone", "engage/core"], function($, _, Backbone, Engage) {
    "use strict";

    var insertIntoDOM = true;
    var PLUGIN_NAME = "Downloads";
    var PLUGIN_TYPE = "engage_tab";
    var PLUGIN_VERSION = "1.0";
    var PLUGIN_TEMPLATE_DESKTOP = "templates/desktop.html";
    var PLUGIN_TEMPLATE_MOBILE = "templates/mobile.html";
    var PLUGIN_TEMPLATE_EMBED = "templates/embed.html";
    var PLUGIN_STYLES_DESKTOP = [
        "styles/desktop.css"
    ];
    var PLUGIN_STYLES_EMBED = [
        "styles/embed.css"
    ];
    var PLUGIN_STYLES_MOBILE = [
        "styles/mobile.css"
    ];

    var plugin;
    var events = {
        plugin_load_done: new Engage.Event("Core:plugin_load_done", "", "handler"),
        mediaPackageModelError: new Engage.Event("MhConnection:mediaPackageModelError", "", "handler")
    };

    var isDesktopMode = false;
    var isEmbedMode = false;
    var isMobileMode = false;

    // desktop, embed and mobile logic
    switch (Engage.model.get("mode")) {
        case "embed":
            plugin = {
                insertIntoDOM: insertIntoDOM,
                name: PLUGIN_NAME,
                type: PLUGIN_TYPE,
                version: PLUGIN_VERSION,
                styles: PLUGIN_STYLES_EMBED,
                template: PLUGIN_TEMPLATE_EMBED,
                events: events
            };
            isEmbedMode = true;
            break;
        case "mobile":
            plugin = {
                insertIntoDOM: insertIntoDOM,
                name: PLUGIN_NAME,
                type: PLUGIN_TYPE,
                version: PLUGIN_VERSION,
                styles: PLUGIN_STYLES_MOBILE,
                template: PLUGIN_TEMPLATE_MOBILE,
                events: events
            };
            isMobileMode = true;
            break;
        case "desktop":
        default:
            plugin = {
                insertIntoDOM: insertIntoDOM,
                name: PLUGIN_NAME,
                type: PLUGIN_TYPE,
                version: PLUGIN_VERSION,
                styles: PLUGIN_STYLES_DESKTOP,
                template: PLUGIN_TEMPLATE_DESKTOP,
                events: events
            };
            isDesktopMode = true;
            break;
    }

    /* change these variables */
    var class_tabGroupItem = "tab-group-item";

    /* don't change these variables */
    var viewsModelChange = "change:views";
    var mediapackageChange = "change:mediaPackage";
    var initCount = 4;
    var mediapackageError = false;
    var translations = new Array();
    var locale = "en";
    var dateFormat = "MMMM Do YYYY, h:mm:ss a";
    var Utils;

    function initTranslate(language, funcSuccess, funcError) {
        var path = Engage.getPluginPath("EngagePluginTabDownloads").replace(/(\.\.\/)/g, "");
        var jsonstr = window.location.origin + "/engage/theodul/" + path; // this solution is really bad, fix it...

        if (language == "de") {
            Engage.log("Tab:Downloads: Chosing german translations");
            jsonstr += "language/de.json";
        } else { // No other languages supported, yet
            Engage.log("Tab:Downloads: Chosing english translations");
            jsonstr += "language/en.json";
        }
        $.ajax({
            url: jsonstr,
            dataType: "json",
            async: false,
            success: function(data) {
                if (data) {
                    data.value_locale = language;
                    translations = data;
                    if (funcSuccess) {
                        funcSuccess(translations);
                    }
                } else {
                    if (funcError) {
                        funcError();
                    }
                }
            },
            error: function(jqXHR, textStatus, errorThrown) {
                if (funcError) {
                    funcError();
                }
            }
        });
    }

    function translate(str, strIfNotFound) {
        return (translations[str] != undefined) ? translations[str] : strIfNotFound;
    }

    function getPixelCount(track) {
      if (!track.video) {
        return 0;
      }

      var pixels = track.video.resolution
                     .split('x')
                     .map(function(dim) {
                       return parseInt(dim);
                     })
                     .reduce(function(collect, dim) {
                       return collect * dim;
                     }, 1);

      return pixels;
    }

    function sortByResolution(tracksForSorting) {
    	tracksForSorting.sort(function(a, b){
    		var resA = getPixelCount(a),
                        resB = getPixelCount(b);

    		if (resA < resB) return 1;
    		if (resA > resB) return -1;
    		return 0;
    	    });
    	return tracksForSorting;
    }

    function sortByType(tracksForSorting){
    	tracksForSorting.sort(function(a, b){
    		var typeA = (typeof a.type === "string") ? a.type : "",
                        typeB = (typeof b.type === "string") ? b.type : "";

    		if (typeA < typeB) return -1;
    		if (typeA > typeB) return 1;
    		return 0;
    	    });
    	return tracksForSorting;
    }

    function sortByMimetype(tracksForSorting){
    	tracksForSorting.sort(function(a, b){
    		var typeA = (typeof a.mimetype === "string") ? a.mimetype : "",
                        typeB = (typeof b.mimetype === "string") ? b.mimetype : "";

    		if (typeA > typeB) return -1;
    		if (typeA < typeB) return 1;
    		return 0;
    	    });
    	return tracksForSorting;
    }

    function momento(dateStr) {
      return ( dateStr.split('T').map(function(str) { return str.replace(/-/g, ''); }))[0];
    }

    function getDownloadList(model) {
        //Filter function for downloads, i.e. use this to remove any streaming/unnecessary tracks
        var list = [];
        _.each(model.get('tracks'), function(item) {
          if (!item.hasOwnProperty('transport') && item.url.indexOf('rtmp') == -1 && item.url.indexOf('flv') === -1) {
              item.url = window.location.protocol + item.url.substring(item.url.indexOf('/'));
              list.push(item);
          }
        });

        list = sortByType(list);
        list = sortByMimetype(list);
        list = sortByResolution(list);

        return list;
    }

    function getCaptionList(model) {
        //Filter function for captions, i.e. use this to remove any other attachments
        var list = [];
        _.each(model.get('attachments'), function(item) {
           // type: "captions/timedtext", mimetype: "text/vtt"
          if (item.type.indexOf('captions') >= 0 || item.mimetype == 'text/vtt') {
              item.url = window.location.protocol + item.url.substring(item.url.indexOf('/'));
              list.push(item);
          }
        });
        return list;
    }

    var DownloadsTabView = Backbone.View.extend({
        initialize: function(mediaPackageModel, template) {
            this.setElement($(plugin.container)); // every plugin view has it's own container associated with it
            this.model = mediaPackageModel;
            this.template = template;
            // bind the render function always to the view
            _.bindAll(this, "render");
            // listen for changes of the model and bind the render function to this
            this.model.bind("change", this.render);
        },
        render: function() {
            if (!mediapackageError) {
                var src = {},
    		    filteredTracks = [],
                captions = [],
    		    mediaSeries = this.model.get("series"),
    		    mediaDate = momento(this.model.get("date")),
    		    downloadURL = "";

                filteredTracks = getDownloadList(this.model);
                captions = getCaptionList(this.model);

                if (mediaSeries) {
                  mediaSeries = (mediaSeries.indexOf(',') > -1 ? mediaSeries.substring(0, mediaSeries.indexOf(',')) : mediaSeries);
                }

        		// Construct a download URL
        		downloadURL =  mediaSeries ? (mediaSeries + "_" + mediaDate) : mediaDate;
                downloadURL = '/download/' + downloadURL
                                               .replace(/\//g, '_')
                                               .replace(/\s/g, '_');
                var tempVars = {
                        str_type: translate("str_type", "Type"),
                    str_mimetype: translate("str_mimetype", "Format"),
                  str_resolution: translate("str_resolution", "Resolution"),
                    str_download: translate("str_download", "Download"),
                          series: this.model.get("series"),
                            date: this.model.get("date"),
                          tracks: filteredTracks,
                        captions: captions,
                     downloadURL: downloadURL
                };

                var tpl = _.template(this.template);
                this.$el.html(tpl(tempVars));
            }
        }
    });

    function initPlugin() {
        // only init if plugin template was inserted into the DOM
        if (isDesktopMode && plugin.inserted) {
            Engage.log("Tab:Downloads initialized");
            var downloadsTabView = new DownloadsTabView(Engage.model.get("mediaPackage"), plugin.template);
            Engage.on(plugin.events.mediaPackageModelError.getName(), function(msg) {
                mediapackageError = true;
            });
            Engage.model.get("views").on("change", function() {
                downloadsTabView.render();
            });
            downloadsTabView.render();
        }
    }

    if (isDesktopMode) {
        // init event
        Engage.log("Tab:Downloads: Init");
        var relative_plugin_path = Engage.getPluginPath("EngagePluginTabDownloads");

        // load utils class

        require([relative_plugin_path + "utils"], function(utils) {
            Engage.log("Tab:Downloads: Utils class loaded");
            Utils = new utils();
            initTranslate(Utils.detectLanguage(), function() {
                Engage.log("Tab:Downloads: Successfully translated.");
                initCount -= 1;
                if (initCount <= 0) {
                    initPlugin();
                }
            }, function() {
                Engage.log("Tab:Downloads: Error translating...");
                initCount -= 1;
                if (initCount <= 0) {
                    initPlugin();
                }
            });
        });

        Engage.model.on(viewsModelChange, function() {
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });

        // listen on a change/set of the mediaPackage model
        Engage.model.on(mediapackageChange, function() {
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });

        // all plugins loaded
        Engage.on(plugin.events.plugin_load_done.getName(), function() {
            Engage.log("Tab:Downloads: Plugin load done");
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });
    }

    return plugin;
});
