/**
 *  Copyright 2009-2011 The Regents of the University of California
 *  Licensed under the Educational Community License, Version 2.0
 *  (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.osedu.org/licenses/ECL-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS IS"
 *  BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 *  or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 *
 */
(function() {
function xhr(params, cb, fail, always) {
  if (typeof params == 'string') {
    params = {url: params};
  }
  if (!params.url) {
    if (fail && typeof fail == 'function') {
      fail(new Error('no URL provided'));
    }
    return;
  }
  params.type = params.type ? params.type.toUpperCase() : 'GET';
  params.data = params.data || null;
  var request = new XMLHttpRequest();
  request.open(params.type, params.url, true);
  request.onload = function() {
    if (request.status < 300) {
      if (cb && typeof cb == 'function') {
        if (params.responseType) {
          switch(params.responseType) {
            case 'json':
              try {
                cb(JSON.parse(request.responseText));
              } catch(e) {
                if (fail && typeof fail == 'function') {
                }
              }
              break;
            case 'document':
              cb(request.response);
              break;
            case 'response':
              cb(request);
              break;
            default:
              cb(request);
              break;
          }
        }
        else cb(request);
      }
    }
    else if (request.stats > 399) {
      if (fail && typeof fail == 'function') {
        fail(request);
      }
    }
    if (always && typeof always == 'function') {
      always();
    }
  };
  request.onerror = function() {
    if (fail && typeof fail == 'function') {
      fail(request);
    }
    if (always && typeof always == 'function') {
      always();
    }
  }
  request.send(params.data);
}

var maxLength = 0;
var courseID = $.getURLParameter("sid"),
    user = [],
    seriesTitle = undefined,
    userURL = "/info/me.json",
    seriesURL = '/api/series/'+ courseID + '/metadata';  

xhr({url: userURL, responseType: 'json'}, 
  function(response) {
    user = response.user;
});

xhr({url: seriesURL, responseType: 'json'}, 
  function(response) {
    seriesTitle = response[0].fields[0].value;
});

$('#feedbackBtn').on('click', function() {
  var parts = [],
      feedbackUrl = "https://docs.google.com/forms/d/e/1FAIpQLSeXmOzYY3rwuB3Plj27kMcI-8B7PpBHkZOo_zi8dd15Zv3u4Q/viewform"; 

  var tempVars = {
    user: user,
    mediaPackage_series: seriesTitle,
    mediaPackage_seriesid: 'S['+courseID+']'
  };  

  if (tempVars.user['email']) {
    parts.push('entry.508626498=' + tempVars.user.email);
  }
  if (tempVars.user['username']) {
    parts.push('entry.277413167=' + tempVars.user.username);
  }
  if (tempVars.mediaPackage_series) {
    parts.push('entry.1631189828=' + tempVars.mediaPackage_series);
  }
  if (tempVars.mediaPackage_seriesid) {
    parts.push('entry.1638000924=' + tempVars.mediaPackage_seriesid);
  }

  $('#feedbackBtn').attr('href', encodeURI(feedbackUrl + (parts.length > 0 ? '?' + parts.join('&') : '')));
});

function manageDownload(el, link) {
  if (isMac()) {
    if (!el.nextElementSibling || !el.nextElementSibling.tagName.toLowerCase != 'a') {
      var dlButton = document.createElement('a');
      dlButton.className = 'btn';
      dlButton.target = '_blank';
      dlButton.innerHTML = 'Download Chosen File';
      el.parentNode.appendChild(dlButton);
    }
    el.nextElementSibling.href = link || (typeof el == 'string' ? el : el.value.replace('http:', 'https:'));
  }
  else {
    var anchor = document.createElement('a');
    anchor.href = link || (typeof el == 'string' ? el : el.value.replace('http:', 'https:'));
    anchor.target = '_blank';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }
}

function getDuration(millis) {
  return new Date(millis).toISOString().substr(11, 8);
}

function isMac() {
  return navigator.platform.indexOf('Mac') > -1;
}

$(document).on("click", ".dlEpisode, .dlCaption", function () {
    var episode_id = $(this).data('episodeId'),
        $el = $('li[data-id="'+ episode_id +'"]');

    console.log(episode_id + ': '+ ($(el).data('downloaded')?'Y':'N'));

    if ($(el).data('downloaded') === false) {
        $el.data('downloaded', true);
        trackUser(episode_id);
    }
});

// $(document).on("click", ".dlCaption", function () {
//     var episodeURL = $(this).data('dlurl'),
//         episodeID = $(this).data('id');

//     window.location = episodeURL;
//     if(clicked) {
//         if(episodeID !== episodeid) {
//             clicked = false;
//             trackUser(episodeID);
//         }
//     }else {
//         trackUser(episodeID);
//     }
// });

function trackUser(episodeID) {
    $.ajax({
        type: 'PUT', 
        dataType: 'json', 
        url:  "/usertracking", 
        headers: {"Accept": "text/plain, */*; q=0.01"},
        data: {"id": episodeID, "type": "VIEWS", "in" : 0}
    });    
}

function sortTable() {
    var table, rows, switching, i, x, y, shouldSwitch;
    table = document.getElementById("mediaTable");
    switching = true;

    while (switching) {
        switching = false;
        rows = table.rows;

        for (i = 1; i < (rows.length - 1); i++) {
            shouldSwitch = false;
            x = rows[i].getElementsByTagName("td")[2];
            y = rows[i + 1].getElementsByTagName("td")[2];
        
            if (x.innerHTML.toLowerCase() > y.innerHTML.toLowerCase()) {
                shouldSwitch = true;
                break;
            }
        }
        if (shouldSwitch) {
            rows[i].parentNode.insertBefore(rows[i + 1], rows[i]);
            switching = true;
        }
    }
}

$(document).on("click", ".downloader", function () {
    if(document.getElementById("mediaLinks")) {
        $('#mediaLinks').html("");
    }

    var episodeTitle = $(this).data('title'),
        episodePresenter = $(this).data('presenter'),
        episodeDate = $(this).data('date'),
        mediaTrack  = $(this).data('package'),
        captions  = $(this).data('captions'),
        episodeID  = $(this).data('id'),
        timestamp = new Date(),
        month = timestamp.getMonth() < 9 ? '0' + (timestamp.getMonth() + 1) : timestamp.getMonth() + 1,
        day = (timestamp.getDate() < 10 ? '0' : '') + timestamp.getDate(),
        dateStamp = "" + timestamp.getFullYear() + month + day;

    $('#titleHolder').html(episodeTitle);
    $('#presenterHolder').html(episodePresenter);
    $('#dateHolder').html(episodeDate);

    if (!Array.isArray(mediaTrack)) {
        mediaTrack = [mediaTrack];
    }

    try {
        var  tBody = document.getElementById('mediaLinks');
        _.forEach(mediaTrack, function(item) {
            var type, quality, videoName, caption_type, 
                tRow = document.createElement('tr'),
                tCol1 = document.createElement('td'),
                tCol2 = document.createElement('td'),
                tCol3 = document.createElement('td'),
                tCol4 = document.createElement('td'),                
                trackType = item.type.split('/'),
                fileType = item.mimetype.split('/');
        
            downloadURL = item.url.replace("http", "https") + '/download/' + episodeTitle + '_' + dateStamp + '_' + trackType[0].charAt(0).toUpperCase() + trackType[0].substring(1) + (fileType[0] === 'audio' ? '.mp3' : '.' + fileType[1]);
            
            if (item.type.indexOf('pic-in-pic') > -1) { 
                type = '_PicInPic';
                videoName = "Picture-in-Picture";
            } else if (item.type.indexOf('composite') > -1) { 
                type = '_SideBySide';
                videoName = "Side By Side";
            } else if (item.type.indexOf('presenter') > -1 &&
                item.mimetype.indexOf("audio") > -1) {
                videoName = "Presenter (audio-only)";
            } else if (item.type.indexOf('presenter') > -1) {
                type = "_Presenter";
                if (caption_type == '') { caption_type = type; }
                videoName = "Presenter (video)";
            } else if (item.type.indexOf('presentation2') > -1) {
                type = "_Presentation";
                if (caption_type == '') { caption_type = type; }
                videoName = "Presentation 2 (video)";
            } else if (item.type.indexOf('presentation') > -1) {
                type = "_Presentation";
                if (caption_type == '') { caption_type = type; }
                videoName = "Presentation (video)";
            } else {
                videoName = "Media track (" + item.type + ")";
            }

            if(typeof  item.video !== "undefined") {
                var vidDims =  item.video.resolution.split('x')
                                .map(function(dim) {
                                    return parseInt(dim);
                                });
                var pixelCount = vidDims[0] * vidDims[1];
                var isLandscape = vidDims[0] > vidDims[1];
                if (pixelCount >= 921600 ){
                    quality = 'High Quality (' +  ( isLandscape ? vidDims[1] + 'p' :  item.video.resolution) + ')';
                } else if (pixelCount >= 307200 ){
                    quality = 'Medium Quality (' + (isLandscape ? vidDims[1] + 'p' :  item.video.resolution) + ')';
                } else {
                    quality = 'Low Quality (' + (isLandscape ? vidDims[1] + 'p' :  item.video.resolution) + ')';
                }
            } else if (item.audio &&  item.audio.bitrate) {
                quality = parseInt(item.audio.bitrate/1000) + "kbps";
            }
            
            tBody.appendChild(tRow);
            tCol1.innerHTML = videoName;
            tCol2.innerHTML = item.mimetype;
            tCol3.innerHTML = quality;
            tCol4.innerHTML = "<a class='btn btn-default btn-sm dlEpisode' data-episodeId='" + episodeID + "' role='button' data-dlurl='" + downloadURL + "'><i class='glyphicon glyphicon-download'></i></a>";
        
            tRow.appendChild(tCol1);
            tRow.appendChild(tCol2);
            tRow.appendChild(tCol3);
            tRow.appendChild(tCol4);
        });   
        
        if(captions && Array.isArray(captions)) {
            _.forEach(captions, function(item) {
                var tCaptionRow = document.createElement('tr'),
                    tCaptionCol1 = document.createElement('td'),
                    tCaptionCol2 = document.createElement('td'),
                    tCaptionCol3 = document.createElement('td'),
                    tCaptionCol4 = document.createElement('td'), 
                    captionDownloadURL = item.url.replace("http", "https") + '/download/';

                tBody.appendChild(tCaptionRow);
                tCaptionCol1.innerHTML = "Caption";
                tCaptionCol2.innerHTML = item.mimetype;
                tCaptionCol3.innerHTML = "";
                tCaptionCol4.innerHTML = "<a class='btn btn-default btn-sm dlCaption' data-episodeId='" + episodeID + "' role='button' data-dlurl='" + captionDownloadURL + "'><i class='glyphicon glyphicon-download'></i></a>";

                tCaptionRow.appendChild(tCaptionCol1);
                tCaptionRow.appendChild(tCaptionCol2);
                tCaptionRow.appendChild(tCaptionCol3);
                tCaptionRow.appendChild(tCaptionCol4);
            });
        }
    } catch(e) {
        console.log(e);
    }
    sortTable();
});

function listEpisode(info) {
    var epiItem = document.createElement('li');
    var recordid = info.id,
        mediaTrack = info.mediapackage.media.track;

    //Various DOM elements to contain episode information
    var picSpan = document.createElement('span'),
        titleSpan = document.createElement('span'),
        creatorSpan = document.createElement('span'),
        dateSpan = document.createElement('span'),
        dlSpan = document.createElement('span'),
        downloadSpan = document.createElement('span'),
        vidLink = document.createElement('a'),
        img = document.createElement('img');
        dlBtn = document.createElement('button'),
        dlIcon = document.createElement('span'),
        dlModal = document.createElement('div');
    epiItem.appendChild(picSpan);
    epiItem.appendChild(titleSpan);
    epiItem.appendChild(creatorSpan);
    epiItem.appendChild(dateSpan);
    epiItem.appendChild(dlSpan);
    dlSpan.appendChild(downloadSpan);
    downloadSpan.appendChild(dlBtn);

    titleSpan.innerHTML = '<span>' + he.encode(info.dcTitle) || '' + '</span>';
    creatorSpan.innerHTML = '<span>' + he.encode(info.dcCreator ? info.dcCreator : '') + '</span>';
    dateSpan.innerHTML = '<span>' + moment(info.dcCreated).format('D MMM YYYY HH:mm') || '' + '</span>';

    vidLink.href = '/engage/theodul/ui/core.html?ltimode=true&id=' + info.id;

    picSpan.appendChild(vidLink);

    //Loop thru pictures to find snapshot of video (append to img tag)
    var attachments = info.mediapackage.attachments.attachment;
    var captions = [];
    var fallback = "";
    for (var i = 0, n = attachments.length; i < n; i++) {
        if (!attachments[i].mimetype) {
            continue;
        } else if (attachments[i].mimetype.indexOf('image') > -1 && attachments[i].type.indexOf('timeline+preview') === -1) {
            fallback = info.mediapackage.attachments.attachment[i].url.replace('http:', 'https:');
            if (attachments[i].type.indexOf('search+preview') > -1) {
                img.src = info.mediapackage.attachments.attachment[i].url.replace('http:', 'https:'); //TODO: proper check to stop mixed-mode
                break;
            }
        } else if (attachments[i].type.indexOf('captions') >= 0 || attachments[i].mimetype == 'text/vtt') {
            captions.push(attachments[i]);
        }
    }
    if (!img.src && fallback) {
        img.src = fallback;
    }
    vidLink.style.backgroundImage = 'url(' + img.src + ')';
    vidLink.setAttribute('data-duration', getDuration(maxLength));

    // design download button
    dlBtn.setAttribute("id", recordid);
    dlBtn.setAttribute("data-toggle", "modal");
    dlBtn.className = "btn btn-primary downloader";
    dlBtn.setAttribute("data-target", "#downloadModal"); 
    dlBtn.setAttribute("data-id", recordid);
    dlBtn.setAttribute("data-title", info.dcTitle);
    dlBtn.setAttribute("data-downloaded", false);
    dlBtn.setAttribute("data-presenter", info.dcCreator);
    dlBtn.setAttribute("data-date", moment(info.dcCreated).format('D MMM YYYY HH:mm'));
    dlBtn.setAttribute("data-package", JSON.stringify(mediaTrack));
    dlBtn.setAttribute("data-captions", JSON.stringify(captions));
    dlBtn.innerHTML = '<i class="glyphicon glyphicon-download"></i> Download';

  //Set data attribute to make item searchable
    var searchableObject = {
        title: info.dcTitle || '',
        createddate: info.dcCreated || '',
        creator: info.dcCreator || ''
    };
    epiItem.setAttribute('data-id', info.id);
    epiItem.setAttribute('data-search', JSON.stringify(searchableObject));
    epiItem.setAttribute('data-title', info.dcTitle || 'track');
    return epiItem;
}

var limit = 10000,
    url = "/search/episode.json?sid=" + (courseID || '') + "&limit=" + limit + "&sort=DATE_PUBLISHED_DESC";

xhr({url: url, responseType: 'json'},
    function(json) {
      document.querySelector('.lti-oc-previous h2')
        .setAttribute('data-total',json['search-results'].total);
      var episodeList = document.querySelector('.lti-oc-all .list');
      if (Array.isArray(json['search-results'].result)) {
        var currYear = new Date();
        var results = json['search-results'].result;
        var results_count = results.length;
        var courseYear = new Date(results[0]["dcCreated"]);

        try {
            if(courseYear.getFullYear() < currYear.getFullYear()) {
                for(var x = results_count-1; x > 0; x--) {
                    episode = results[x];
                    episodeList.appendChild( listEpisode(episode) );
                    document.querySelector('.sorting').setAttribute('data-sort', 'asc');
                }
            } else {
                json['search-results'].result.forEach(function(episode) {
                    episodeList.appendChild( listEpisode(episode) );
                });
            }
        } catch (e) {
            console.log(e);
        }
      }
      else if (typeof json['search-results'].result === 'object' && json['search-results'].result != null) {
        episodeList.appendChild( listEpisode(json['search-results'].result) );
      }

      if (window.self !== window.top) {
        window.top.postMessage(JSON.stringify({
          subject: "lti.frameResize",
          height: document.body.clientHeight + 40
        }), "*");
      }
    },
    function(err) {
      console.log(err);
    }
);

var latestEpisodesURL = '/search/episode.json?sid=' + (courseID || '') + '&limit=3&sort=DATE_CREATED_DESC';         //fetch latest 3 (max) episodes for series

xhr({url: latestEpisodesURL, responseType: 'json'}, 
  function(response) {
    var latestContainer = document.querySelector('.lti-oc-recent');
    response['search-results'].result.forEach( function(episode) {
      latestContainer.appendChild( listEpisode(episode) );
    });
});

$('#manageNotificationModal').on('click', ' .btn-default, .close', function(e) {
  $('#manageNotificationModal').removeClass('in');
});

$('#neverRemindManagement').on('change', function(e) {
  if (window.localStorage) {
    localStorage.setItem('manageNotify', $(this)[0].checked);
  }
});

function toggleFilter(e) {
  this.parentNode
    .querySelector('li.filter')
      .classList.toggle('active');
}

var EventsTable = function() {
  this.searchObj = {
        title: '',
    startDate: '',
      endDate: ''
  };
  this.filters = [];

  //default event-retrieving parameters
  this.events = {
    base: {
      limit: 3,
      url: '/search/episode.json?sid=' + courseID,
      total: 0,
      offset: 0,
    }
  }
  this.fetching = false;

  //results of event search
  var self = this;
  var clearFiltersButton = document.querySelector('button[name="clearFilters"]');
  //Define event listeners
  this.filterResults = function(e) {
    var elements = self.getTableElements();
    elements.forEach(function(element, i) {
      if (self.searchFound(element.getAttribute('data-search'))) {
        element.style.display = '';
      }
      else {
        element.style.display = 'none';
      }
    });
    if (!clearFiltersButton.classList.contains('display')) clearFiltersButton.classList.add('display');
  }

  this.searchFound = function(searchStr) {
    var found = (searchStr.toLowerCase().indexOf(this.filters[0].value.toLowerCase()) > -1 ? true : false);
  
    try {
      var searchDates = JSON.parse(searchStr);
      if (this.filters[1].value) {
        found *= (new Date(this.filters[1].value).getTime() < new Date(searchDates.createddate).getTime() ? true : false);
      }
      if (this.filters[2].value) {
        if (this.filters[1].value && this.filters[1].value === this.filters[2].value) {
          found *= (new Date(this.filters[2].value).getTime()/1000 + 86400 > new Date(searchDates.createddate).getTime()/1000 ? true : false);
        } else {
          found *= (new Date(this.filters[2].value).getTime() > new Date(searchDates.createddate).getTime() ? true : false);
        }
      }
    } catch (e) {
      console.log(e);
    }
    return found;
  }

  this.clearFilters = function(e) {
    self.filters.forEach(function(filter) {
      filter.value = '';
    });
    self.filters[0].dispatchEvent(new Event('keyup'));
    this.classList.remove('display');
  }

  this.getTableElements = function() {
    return Array.prototype.slice.call(document.querySelectorAll('.lti-oc-all .list li'));
  };

  this.toggleSort = function(e) {
    var sortDir = this.getAttribute('data-sort') === 'asc' ? 'desc' : 'asc';
    this.setAttribute('data-sort', sortDir);
    var curSort = this.parentNode.querySelector('.sorting');
    if (curSort) {
      curSort.classList.remove('sorting');
    }
    this.classList.add('sorting');
    self.performSort(this.getAttribute('data-column'), sortDir);
  };

  this.performSort = function(col, sortDir) {
    var eventList = document.querySelector('.lti-oc-all .list');
    self.sort = {
      sort: col,
      direction: sortDir
    }
    var elements = self.getTableElements();
    var elData = [];
    console.log('sorting ' + col);
    elements.forEach(function(element) {
      var data = JSON.parse(element.getAttribute('data-search'));
      data.element = element;
      elData.push(data);
      eventList.removeChild(element);
    });
    elData.sort(function(a, b) {
      if (col === 'createddate') {
        if (sortDir === 'desc') {
          if (new Date(a[col]).getTime() < new Date(b[col]).getTime()) {
            return 1;
          }
          else {
            return -1;
          }
        } else {
          if (new Date(a[col]).getTime() < new Date(b[col]).getTime()) {
            return -1;
          }
          else {
            return 1;
          }
        }
      }
      else {
        if (sortDir === 'desc') {
          if (a[col].toLowerCase().trim() < b[col].toLowerCase().trim()) {
            return 1;
          }
          else if (a[col].toLowerCase().trim() > b[col].toLowerCase().trim()) {
            return -1;
          }
          else return 0;
        } else {
          if (a[col].toLowerCase().trim() < b[col].toLowerCase().trim()) {
            return -1;
          }
          else if (a[col].toLowerCase().trim() > b[col].toLowerCase().trim()) {
            return 1;
          }
          else return 0;
        }
      }
    }).forEach(function(el) {
      eventList.appendChild(el.element);
    });
    console.log(elData);
  };

  //Attach events
  Array.prototype.slice.call(document.querySelectorAll('.filter input'))
    .forEach(function(filter, i) {
      self.filters.push(filter);
      filter.addEventListener('keyup', self.filterResults, false);
      if (i > 0) {
        $(filter).datepicker({
          dateFormat: 'yy-mm-dd',
          onSelect: function() {
            $(this)[0].dispatchEvent(new Event('keyup'));
          }
        });
        $(filter).on('click', function(e) {
          if ($(this).is(':focus')) {
            $(this)[0].dispatchEvent(new Event('focus'));
          }
        });
      }
    });

  Array.prototype.slice.call(document.querySelectorAll('.lti-oc-all span[data-sort]'))
    .forEach(function(el) {
      el.addEventListener('click', self.toggleSort, false);
    });

  clearFiltersButton.addEventListener('click', self.clearFilters, false);
}

var eTable = new EventsTable();

})();
