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

function downloadTrack(e) {
  if (!this.value) {
    return;
  }
  var url = this.value.replace('http:', 'https:');
  var button = this.nextElementSibling;
  button.href = url;
  xhr({url: url, type: 'HEAD', responseType: 'response'}, function(res) {
    if (res.status == 200) {
      if (res.getResponseHeader('Content-Disposition')) {
        var unitName = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
        var fileSize = parseInt(res.getResponseHeader('Content-Length'));
        var unit = 0;
        while (fileSize > 1024 && unit < 6) {
          fileSize /= 1024;
          unit++;
        }
        button.innerHTML = 'Download (' + parseInt(fileSize) + unitName[unit] + ')';
   //     window.location = url;
      }
      else {
        button.innerHTML = 'Download (Size unknown)';
      }
    }
    else if (res.status == 404) {
      alert('Unfortunately, that file could not be found. Please try another');
    }
  }, function(err) {
    alert('Apologies, an error occurred in sourcing that file. The administrators have been contacted');
  });
}

function getDuration(millis) {
  return new Date(millis).toISOString().substr(11, 8);
}

function isMac() {
  return navigator.platform.indexOf('Mac') > -1;
}

function listEpisode(info) {
  var epiItem = document.createElement('li');

  //Various DOM elements to contain episode information
  var picSpan = document.createElement('span'),
      titleSpan = document.createElement('span'),
      creatorSpan = document.createElement('span'),
      dateSpan = document.createElement('span'),
      dlSpan = document.createElement('span'),
      dlSelect = document.createElement('select'),
      dlButton = document.createElement('a'),
      vidLink = document.createElement('a'),
      img = document.createElement('img');
  epiItem.appendChild(picSpan);
  epiItem.appendChild(titleSpan);
  epiItem.appendChild(creatorSpan);
  epiItem.appendChild(dateSpan);
//  epiItem.appendChild(dlSpan);

  titleSpan.innerHTML = '<span>' + he.encode(info.dcTitle) || '' + '</span>';
  creatorSpan.innerHTML = '<span>' + he.encode(info.dcCreator ? info.dcCreator : '') + '</span>';
  dateSpan.innerHTML = '<span>' + moment(info.dcCreated).format('D MMM YYYY HH:mm') || '' + '</span>';

  vidLink.href = '/engage/theodul/ui/core.html?ltimode=true&id=' + info.id;

  picSpan.appendChild(vidLink);

  //Loop thru pictures to find snapshot of video (append to img tag)
  var attachments = info.mediapackage.attachments.attachment;
  var fallback = "";
  for (var i = 0, n = attachments.length; i < n; i++) {
    if (!attachments[i].mimetype) {
      continue;
    }
    else if (attachments[i].mimetype.indexOf('image') > -1 && attachments[i].type.indexOf('timeline+preview') === -1) {
      fallback = info.mediapackage.attachments.attachment[i].url.replace('http:', 'https:');
      if (attachments[i].type.indexOf('search+preview') > -1) {
        img.src = info.mediapackage.attachments.attachment[i].url.replace('http:', 'https:'); //TODO: proper check to stop mixed-mode
        break;
      }
    }
  }
  if (!img.src && fallback) {
    img.src = fallback;
  }
  vidLink.style.backgroundImage = 'url(' + img.src + ')'

  dlSpan.innerHTML = '<span></span>';
  dlButton.innerHTML = 'Download';
  dlButton.classList.add('button');
  dlButton.target = '_blank';

  //Populate select element with downloadable links
  dlSpan.querySelector('span').appendChild(dlSelect);
  dlSpan.querySelector('span').appendChild(dlButton);
  dlSelect.innerHTML = '<option value="">--Select Media for Download--</option>';
  var mediaTracks = info.mediapackage.media.track;
  var maxLength = 0;
  var timestamp = new Date();
  var month = timestamp.getMonth() < 9 ? '0' + (timestamp.getMonth() + 1) : timestamp.getMonth() + 1;
  var day = (timestamp.getDate() < 10 ? '0' : '') + timestamp.getDate();
  var dateStamp = "" + timestamp.getFullYear() + month + day;    //TODO: consider multiple lectures on the same day
  if (!Array.isArray(mediaTracks)) {
    mediaTracks = [mediaTracks];
  }
  mediaTracks.forEach( function(mediaTrack) {
    var trackType = mediaTrack.type.split('/');
    var fileType = mediaTrack.mimetype.split('/');
    var opt = document.createElement('option');
    opt.value = mediaTrack.url + '/download/' + info.dcTitle + '_' + dateStamp + '_' + trackType[0].charAt(0).toUpperCase() + trackType[0].substring(1) + (fileType[0] === 'audio' ? '.mp3' : '.' + fileType[1]) ;
    try {
      var typeText = (mediaTrack.type.split('/'))[0];
      opt.innerHTML = he.encode(typeText.charAt(0).toUpperCase() + typeText.substring(1) + ' - ' + (mediaTrack.hasOwnProperty('video') ? 'Video @ ' + mediaTrack.video.resolution : 
                          (mediaTrack.hasOwnProperty('audio') ? 'Audio' : "Undetermined Track")));
    } catch(e) {
      console.log(e);
    }
    dlSelect.appendChild(opt);
    if (maxLength < mediaTrack.duration) {
      maxLength = mediaTrack.duration;
    }
  });
  
  //Event listener for the downloading of media tracks
  dlSelect.addEventListener('change', downloadTrack, false);
  vidLink.setAttribute('data-duration', getDuration(maxLength));

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

    var courseID = $.getURLParameter("sid"),
        limit = 10000,
        url = "/search/episode.json?sid=" + (courseID || '') + "&limit=" + limit + "&sort=DATE_PUBLISHED_DESC";

xhr({url: url, responseType: 'json'},
    function(json) {
      document.querySelector('.lti-oc-previous h2')
        .setAttribute('data-total',json['search-results'].total);
      var episodeList = document.querySelector('.lti-oc-all .list');
      if (Array.isArray(json['search-results'].result)) {
        json['search-results'].result.forEach(function(episode) {
          episodeList.appendChild( listEpisode(episode) );
        });
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
