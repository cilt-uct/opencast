const amathuba_url = 'amathuba.uct.ac.za',
      timetable_url = 'https://srvubuclt001.uct.ac.za/timetable/?course=';

function parseCourses(input) {
    // Extract the year at the end of the string
    const yearMatch = input.match(/\d{4}$/);
    if (!yearMatch) {
        throw new Error("Year not found in the string");
    }
    const year = yearMatch[0];

    // Remove the year and split the string into course codes
    const courses = input.replace(`_${year}`, "").split("_");

    // Map each course code to the desired format
    return courses.map(course => `${course},${year}`);
}

function Timetable(params, isPersonal) {
    var _ltiData = (params || {}).lti;
    var _eventMgr = (params || {}).eventManager;

    var _completeFns = [];
    Object.defineProperty(this, 'oncomplete', {
        get: function () {
            return _completeFns;
        },
        set: function (fn) {
            if (typeof fn == 'function') {
                _completeFns.push(fn);
            }
        }
    });

    var _resolved = false;
    Object.defineProperty(this, 'resolved', {
        get: function () {
            return _resolved;
        },
        set: function (bool) {
            if (typeof bool == 'boolean') {
                _resolved = bool;
                this.emit();
            }
        }
    });

    Object.defineProperty(this, 'isPersonal', {
        get: function () {
            return isPersonal;
        },
        enumerable: false
    });

    _ltiData.on('complete', function () {
        if (this.isPersonal) {
            return;
        }

        var _ttArr = [];
        if (_ltiData['lis_course_offering_sourcedid'].indexOf(amathuba_url) === 0) {
            let _source = _ltiData['lis_course_offering_sourcedid']
                .replace(`${amathuba_url}:`, '')
                .split('+')
                .map(str => str.trim());

            // Use flatMap to parse courses and process each with setCourseTimetable
            _source.flatMap(parseCourses).forEach(function (course) {
                _ttArr.push(this.setCourseTimetable(course)); // Collect promises
            }.bind(this));
        } else {
            (_ltiData['lis_course_offering_sourcedid'] || '')
                .split('+')
                .forEach(function (course) {
                    _ttArr.push(this.setCourseTimetable(course)); // Collect promises
                }.bind(this));
        }

        // Wait for all promises in _ttArr to resolve
        $.when.apply($, _ttArr).done(function () {
            this.resolved = true; // Trigger the complete event after all AJAX calls are done
        }.bind(this));
    }.bind(this));
}

Timetable.prototype = {
    constructor: Timetable,
    fetchTimetable: function (course) {
        return $.Deferred(function (d) {
            var timetable = null;

            course = course.split(",");
            if (course.length === 2 || !isNaN(course[0])) {
                $.ajax({
                    url: timetable_url + course,
                    method: 'GET'
                })
                    .done(function (tt) {
                        timetable = tt;
                    })
                    .always(function () {
                        d.resolve(timetable);
                    });
            }
        }).promise();
    },
    setCourseTimetable: function (course) {
        return $.Deferred(function (d) {
            this.fetchTimetable(course)
                .then(function (tt) {
                    if (tt && tt.course) {
                        this[tt.course + ',' + tt.term] = tt;
                    }
                    d.resolve();
                }.bind(this));
        }.bind(this)).promise();
    },
    on: function (event, fn) {
        if (typeof fn != 'function' || event !== 'complete') {
            return;
        }

        if (this.resolved) {
            fn(this);
            return;
        }
        this.oncomplete = fn;
    },
    emit: function () {
        this.oncomplete.forEach(function (fn) {

            fn(this);
        }.bind(this));
    }
}
