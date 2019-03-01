/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

/*jslint browser: true, nomen: true*/
/*global define*/
define(["jquery", "underscore", "backbone", "engage/core"], function($, _, Backbone, Engage) {
    "use strict";

    var insertIntoDOM = true;
    var PLUGIN_NAME = "Feedback";
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
    var feedback = [];
    var categories = [];
    var initCount = 3;
    var infoMeChange = "change:infoMe";
    var mediapackageChange = "change:mediaPackage";
    var translations = [];
    var mediapackageError = false;
    var feedbackParsed = false;
    var categoriesParsed = false;

    function initTranslate(language) {
        var path = Engage.getPluginPath("EngagePluginTabFeedback").replace(/(\.\.\/)/g, "");
        var jsonstr = window.location.origin + "/engage/theodul/" + path; // this solution is really bad, fix it...

        Engage.log("Controls: selecting language " + language);
        jsonstr += "language/" + language + ".json";
        $.ajax({
            url: jsonstr,
            dataType: "json",
            success: function(data) {
                if (data) {
                    data.value_locale = language;
                    translations = data;
                }
            }
        });
    }

    function translate(str, strIfNotFound) {
        return translations[str] || strIfNotFound;
    }

    var FeedbackTabView = Backbone.View.extend({
        initialize: function(mediaPackageModel, template) {
            this.setElement($(plugin.container));
            this.model = mediaPackageModel;
            this.template = template;
            // bind the render function always to the view
            _.bindAll(this, "render");
            // listen for changes of the model and bind the render function to this
            this.model.bind("change", this.render);
        },
        render: function() {
            if (!mediapackageError) {
                var tempVars = {
                    categories: categories,
                    str_feedback: translate("feedback", "Feedback"),
                    user: Engage.model.get("meInfo").get("user"),
                    mediaPackage_title: Engage.model.get('mediaPackage').get('title'),
                    mediaPackage_eventid: 'E['+Engage.model.get('mediaPackage').get('eventid')+']',
                    mediaPackage_series: Engage.model.get('mediaPackage').get('series'),
                    mediaPackage_seriesid: 'S['+Engage.model.get('mediaPackage').get('seriesid')+']',
                    mediaPackage_date: Engage.model.get('mediaPackage').get('date')
                };

                var feedback_url = "https://docs.google.com/forms/d/e/1FAIpQLSeXmOzYY3rwuB3Plj27kMcI-8B7PpBHkZOo_zi8dd15Zv3u4Q/viewform",
                    parts = [];

                if (tempVars.user['email']) {
                    parts.push('entry.508626498=' + tempVars.user.email);
                }
                if (tempVars.user['username']) {
                    parts.push('entry.277413167=' + tempVars.user.username);
                }
                if (tempVars.mediaPackage_series) {
                    parts.push('entry.1631189828=' + tempVars.mediaPackage_series);
                }
                if (tempVars.mediaPackage_eventid && tempVars.mediaPackage_seriesid) {
                    parts.push('entry.1638000924=' + tempVars.mediaPackage_seriesid + tempVars.mediaPackage_eventid);
                }
                if (tempVars.mediaPackage_date) {
                    var dt = new Date(tempVars.mediaPackage_date)
                    parts.push('entry.1375271116_year=' + dt.getFullYear());
                    parts.push('entry.1375271116_month=' + (dt.getMonth()+1));
                    parts.push('entry.1375271116_day=' + dt.getDate());
                }

                // compile template and load into the html
                var template = _.template(this.template);
                this.$el.html(template(tempVars));

                $("#engage_tab_" + plugin.name.replace(/\s/g,"_")).text(tempVars.str_feedback);
                $("#engage_feedback_tab_content a.button").attr('href', encodeURI(feedback_url + (parts.length > 0 ? '?' + parts.join('&') : '')));
            }
        }
    });

    function translateKeyboardCombination(comb, split) {
        if (comb.indexOf(split) != -1) {
            var spl = comb.split(split);
            var ret = "";
            for (var i = 0; i < spl.length; ++i) {
                if (i > 0) {
                    ret += "+";
                }
                // filter the mod modifier
                if (spl[i] == "mod") {
                    if (navigator.platform && (navigator.platform.indexOf("Mac") != -1)) {
                        ret += translate("modMac", "cmd");
                    } else {
                        ret += translate("modOther", "ctrl");
                    }
                } else {
                    ret += translate(spl[i], spl[i]);
                }
            }
            return ret;
        }
        return translate(comb, comb);
    }

    function prepareCategories() {
        if (!categoriesParsed) {
            var categorySequenceString = Engage.model.get("meInfo").get("feedback-sequence");
            var categorySequence = [];
            if (typeof categorySequenceString !== "undefined")
                categorySequence = categorySequenceString.split(',');
            if (categorySequence) {
                $.each(categorySequence, function(i, v) {
                    categories.push({
                        name: translate(v, v),
                        val: v
                    });
                    feedback[v] = [];
                });
                categoriesParsed = true;
            }
        }
    }

    function initPlugin() {
        // only init if plugin template was inserted into the DOM
        if (isDesktopMode && plugin.inserted) {
            initTranslate(Engage.model.get("language"));
            // create a new view with the media package model and the template
            var feedbackTabView = new FeedbackTabView(Engage.model.get("mediaPackage"), plugin.template);
            Engage.on(plugin.events.mediaPackageModelError.getName(), function(msg) {
                mediapackageError = true;
            });
        }
    }

    if (isDesktopMode) {
        // init event
        Engage.log("Tab:Feedback: Init");
        var relative_plugin_path = Engage.getPluginPath("EngagePluginTabFeedback");

        // listen on a change/set of the mediaPackage model
        Engage.model.on(mediapackageChange, function() {
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });

        // listen on a change/set of the infoMe model
        Engage.model.on(infoMeChange, function() {
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });

        // all plugins loaded
        Engage.on(plugin.events.plugin_load_done.getName(), function() {
            Engage.log("Tab:Feedback: Plugin load done");
            initCount -= 1;
            if (initCount <= 0) {
                initPlugin();
            }
        });
    }

    return plugin;
});
