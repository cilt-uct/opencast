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
    var PLUGIN_NAME = "Transcript";
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
        mediaPackageModelError: new Engage.Event("MhConnection:mediaPackageModelError", "", "handler"),
        timeupdate: new Engage.Event('Video:timeupdate', 'notices a timeupdate', 'handler'),
        seek: new Engage.Event('Video:seek', 'seek video to a given position in seconds', 'trigger')
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
    var Utils;
    var vttObjects = {};
    var currentTime = 0;
    var startTime = "startTime";
    var endTime = "endTime";
    var text = "text";

    function initTranslate(language, funcSuccess, funcError) {
        var path = Engage.getPluginPath("EngagePluginTabTranscript").replace(/(\.\.\/)/g, "");
        var jsonstr = window.location.origin + "/engage/theodul/" + path;

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
            success: function (data) {
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
            error: function (jqXHR, textStatus, errorThrown) {
                if (funcError) {
                    funcError();
                }
            }
        });
    }

    function getCaptionList(model) {

        var captions = _.find(model.get('attachments'), function (item) {
            // type: "captions/timedtext", mimetype: "text/vtt"
            // assumption is that there is only one vvt file, FOR NOW!
            if (item.type.indexOf('captions') >= 0 || item.mimetype == 'text/vtt') {
                item.url = window.location.protocol + item.url.substring(item.url.indexOf('/'));
                return item;
            }
        });

        return captions;
    }

    function getVTT(captions) {
        var request = new XMLHttpRequest();
        request.open('GET', captions["url"], false);
        request.send(null);
        return request.responseText;
    }

    var DownloadsTabView = Backbone.View.extend({
        initialize: function (mediaPackageModel, template) {
            this.setElement($(plugin.container)); // every plugin view has it's own container associated with it
            this.model = mediaPackageModel;
            this.template = template;
            // bind the render function always to the view
            _.bindAll(this, "render");
            // listen for changes of the model and bind the render function to this
            this.model.bind("change", this.render);
        },
        render: function () {
            if (!mediapackageError) {
                var vttText = [];

                var captions = getCaptionList(this.model);

                if (!_.isUndefined(captions)) {
                    var vtt = getVTT(captions);
                    vttText = vtt.split('\n\n');

                    for (var i = 1; i < vttText.length; i++) {
                        buildVTTObject(i, vttText)
                    }
                }

                var tempVars = {
                    vttObjects: vttObjects
                };

                var tpl = _.template(this.template);
                this.$el.html(tpl(tempVars));
                addListeners(vttText);
            }
        }
    });

    function addListeners(vttText) {
        document.getElementById("transcript_tab_search").addEventListener("keyup", filterText);

        for (var i = 1; i < vttText.length; i++) {
            document.getElementById(i).addEventListener("click", updateVideo);
        }
    }

    function filterText() {
        var searchTerm = this.value;

        _.each(vttObjects, function (object, key) {
            var element = document.getElementById(key);
            if (!vttObjects[key][text].toLowerCase().includes(searchTerm.toLowerCase())) {
                element.classList.add("greyout");
            }
            else {
                element.classList.remove("greyout");
            }
        });
        
    }

    function buildVTTObject(index, vttText) {
        var timeAndText = vttText[index].split(/(?<=\d{3})\s+(?=\w)/);
        var times = timeAndText[0].split(' ');
        var timeAndTextObject = {}
        timeAndTextObject[startTime] = Utils.getTimeInMilliseconds(times[0]);
        timeAndTextObject[endTime] = Utils.getTimeInMilliseconds(times[2]);
        timeAndTextObject[text] = timeAndText[1];
        vttObjects[index] = timeAndTextObject;
    }

    function updateVideo() {
        var timeObject = vttObjects[this.id]
        var time = timeObject[startTime];
        Engage.trigger(plugin.events.seek.getName(), time / 1000);
    }

    function initPlugin() {
        // only init if plugin template was inserted into the DOM
        if (isDesktopMode && plugin.inserted) {
            Engage.log("Tab:Downloads initialized");
            var downloadsTabView = new DownloadsTabView(Engage.model.get("mediaPackage"), plugin.template);
            Engage.on(plugin.events.mediaPackageModelError.getName(), function (msg) {
                mediapackageError = true;
            });
            Engage.model.get("views").on("change", function () {
                downloadsTabView.render();
            });
            downloadsTabView.render();
        }
    }

    if (isDesktopMode) {
        // init event
        Engage.log("Tab:Downloads: Init");
        var relative_plugin_path = Engage.getPluginPath("EngagePluginTabTranscript");

        // load utils class

        require([relative_plugin_path + "utils"], function (utils) {
            Engage.log("Tab:Downloads: Utils class loaded");
            Utils = new utils();
            initTranslate(Utils.detectLanguage(), function () {
                Engage.log("Tab:Downloads: Successfully translated.");
                initCount -= 1;
                if (initCount <= 0) {
                    initPlugin();
                }
            }, function () {
                Engage.log("Tab:Downloads: Error translating...");
                initCount -= 1;
                if (initCount <= 0) {
                    initPlugin();
                }
            });
        });

        Engage.model.on(viewsModelChange, function () {
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });

        // listen on a change/set of the mediaPackage model
        Engage.model.on(mediapackageChange, function () {
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });

        // all plugins loaded
        Engage.on(plugin.events.plugin_load_done.getName(), function () {
            Engage.log("Tab:Downloads: Plugin load done");
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });

        Engage.on(plugin.events.timeupdate.getName(), function (_currentTime) {
            if (!mediapackageError) {
                currentTime = _currentTime;
            }
        });
    }

    return plugin;
});
