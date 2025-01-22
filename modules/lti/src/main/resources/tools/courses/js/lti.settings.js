const urlParams = new URLSearchParams(window.location.search);
const seriesID = urlParams.get('sid');

$(document).ready(function(){
    var seriesInfo = getSeries("/api/series/" + seriesID + "/metadata");
    var dublin = seriesInfo[0].fields;
    var ext = seriesInfo[1].fields;
    const aiFeaturesField = document.getElementById('ai_features');
    const aiTranscriptionCheckboxes = document.querySelectorAll('#ai_features_options .form-check-input');


    // Update ai-features hidden input when checkboxes are checked/unchecked
    aiTranscriptionCheckboxes.forEach(checkbox => {
        checkbox.addEventListener('change', () => {
            const selectedFeatures = Array.from(aiTranscriptionCheckboxes)
                .filter(cb => cb.checked)
                .map(cb => cb.value);
            aiFeaturesField.value = selectedFeatures.join(',');
       });
    });


    for (var x=0; x < dublin.length; x++) {
        if(dublin[x].id == "title") {
            $('#series_title').val(dublin[x].value);
        }
        if(dublin[x].id == "createdBy") {
            $('#series_creator').val(dublin[x].value);
        }
    }

    for (var j=0; j < ext.length; j++) {
        if(ext[j].id == "retention-cycle") {
            if(ext[j].value == "normal" || ext[j].value == "") {
                $('#series_retention  option[value=normal]').attr('selected','selected');
            } else if(ext[j].value == "long") {
                $('#series_retention  option[value=long]').attr('selected','selected');
            } else if(ext[j].value == "forever") {
                $('#series_retention  option[value=forever]').attr('selected','selected');
            }
        }
        if(ext[j].id == "caption-type") {
            if(ext[j].value == "none") {
                $('#series_captions  option[value=none]').attr('selected','selected');
            } else if(ext[j].value == "google") {
                $('#series_captions  option[value=google]').attr('selected','selected');
            } else if(ext[j].value == "") {
                $('#series_captions  option[value=no_selection]').attr('selected','selected');
            }
        }
        if(ext[j].id == "series-locked") {
            if(ext[j].value == false) {
                $('#locked_status').val("No");
            } else {
                $('#locked_status').val("Yes");
            }
        }
        if(ext[j].id == "series-expiry-date") {
            if(ext[j].value != '') {
                $('#retain_date').val(ext[j].value);
            } else {
                $('#retain_date').val('');
            }
        }
        if(ext[j].id == "notification-list") {
            if(ext[j].value != '') {
                $('#notification_list').val(ext[j].value);
            } else {
                $('#notification_list').val('');
            }
        }
        if (ext[j].id == "transcription-type") {
            if (ext[j].value != '') {
                $('#ai_features').val(ext[j].value);
            } else {
                $('#ai_features').val('');
            }
        }
    }

    // Pre-check transcription checkboxes based on hidden input value
    const aiFeatures = aiFeaturesField.value.split(',').map(feature => feature.trim());
    // Always show/select transcript
    if (!aiFeatures.includes('transcript')) {
        aiFeatures.push('transcript');
        aiFeaturesField.value = aiFeatures.join(',');
    }

    aiTranscriptionCheckboxes.forEach(checkbox => {
        if (aiFeatures.includes(checkbox.value)) {
            checkbox.checked = true;
        }
    });

    $("#save_button").click(function(e) {
        e.preventDefault();
        var captions = $('#series_captions').val();
        var retention = $('#series_retention').val();
        var notificationList = $('#notification_list').val();
        var aiFeatures = $('#ai_features').val();

        var notificationListArray = notificationList.split(';').map(email => email.trim()).filter(email => email.length > 0);
        var aiFeaturesArray = aiFeatures.split(',').map(feature => feature.trim()).filter(feature => feature.length > 0);

        var fd = new FormData();
        const newExt = ext.map(obj => {
            switch (obj.id) {
                case "caption-type":
                    return { ...obj, value: captions };
                case "retention-cycle":
                    return { ...obj, value: retention };
                case "notification-list":
                    return { ...obj, value: notificationListArray };
                case "transcription-type":
                    return { ...obj, value: aiFeaturesArray};
                default:
                    return obj;
            }
        });

        const updatedMetadata = seriesInfo.map(obj => obj.flavor === "ext/series" ? { ...obj, fields: newExt } : obj);

        var metadata = JSON.stringify(updatedMetadata);
        fd.append("metadata", metadata);
        $("#processingModal").modal('show');
        updateSeriesMetadata(fd);
    });
});

function getSeries(url) {
    return JSON.parse($.ajax({
        type: 'GET',
        url: url,
        dataType: 'json',
        global: false,
        async: false,
        success: function (data) {
           return data;
        }
    }).responseText);
}

function updateSeriesMetadata(fd) {
    var url = "/api/series/" + seriesID;

    $.ajax({
        type: "PUT",
        url: url,
        processData: false,
        contentType: false,
        data: fd,
        dataType: "json"
    }).done(function (data) {
        $("#processingContent").hide();
        $("#resultMessage").text("Series settings updated successfully!");
        $("#resultContent").show();
    }).fail(function (jqXHR, textStatus, errorThrown) {
        $("#processingContent").hide();
        $("#resultMessage").text("Failed to update series settings. Please try again.");
        $("#resultContent").show();
        console.error("Update failed:", textStatus, errorThrown);
        console.log("Response Text:", jqXHR.responseText);
    });
}