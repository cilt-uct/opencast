const urlParams = new URLSearchParams(window.location.search);
const seriesID = urlParams.get('sid');
const seriesCaptionsDropdown = document.getElementById("series_captions");
const seriesCaptionsLabel = document.getElementById("series_captions_label");
const transcriptFeaturesField = document.getElementById('transcript_features');
const transcriptFeaturesCheckboxes = document.querySelectorAll('#transcript_features_options .form-check-input');


$(document).ready(function(){
    var seriesInfo = getSeries("/api/series/" + seriesID + "/metadata");
    var dublin = seriesInfo[0]?.fields;
    var ext = seriesInfo[1]?.fields;

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
            let captionValue = ext[j].value.trim();

            if(captionValue === "nibity") {
                $('#series_captions').hide();
                $('#series_captions_label').text("WayWithWords").show();
            } else {
                $('#series_captions').show();
                $('#series_captions_label').hide();
                $('#series_captions option[value=' + captionValue + ']').prop('selected', true);
            }
            toggleAIFeatures();
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
        if (ext[j].id == "transcript-features") {
            if (ext[j].value != '') {
                $('#transcript_features').val(ext[j].value);
            } else {
                $('#transcript_features').val('');
            }
        }
    }

    // Pre-check transcription checkboxes based on hidden input value
    const transcriptFeatures = transcriptFeaturesField.value.split(',').map(feature => feature.trim());

    transcriptFeaturesCheckboxes.forEach(checkbox => {
        if (transcriptFeatures.includes(checkbox.value)) {
            checkbox.checked = true;
        }
    });

    // Update transcript-features hidden input when checkboxes are checked/unchecked
    transcriptFeaturesCheckboxes.forEach(checkbox => {
        checkbox.addEventListener('change', () => {
            const selectedFeatures = Array.from(transcriptFeaturesCheckboxes)
                .filter(cb => cb.checked)
                .map(cb => cb.value);
            transcriptFeaturesField.value = selectedFeatures.join(',');
       });
    });

    $("#save_button").click(function(e) {
        e.preventDefault();
        var captions = $('#series_captions').is(':visible') ? $('#series_captions').val() : 'nibity';
        var retention = $('#series_retention').val();
        var notificationList = $('#notification_list').val();
        var transcriptFeatures = $('#transcript_features').val();

        console.log("Captions dropdown value:", $('#series_captions').val());
        console.log("captions label value: ", captions);

        var notificationListArray = notificationList.split(';').map(email => email.trim()).filter(email => email.length > 0);
        var transcriptFeaturesArray = transcriptFeatures.split(',').map(feature => feature.trim()).filter(feature => feature.length > 0);

        var fd = new FormData();
        const newExt = ext.map(obj => {
            switch (obj.id) {
                case "caption-type":
                    return { ...obj, value: captions };
                case "retention-cycle":
                    return { ...obj, value: retention };
                case "notification-list":
                    return { ...obj, value: notificationListArray };
                case "transcript-features":
                    return { ...obj, value: transcriptFeaturesArray};
                default:
                    return obj;
            }
        });

        
        const updatedMetadata = seriesInfo.map(obj => obj.flavor === "ext/series" ? { ...obj, fields: newExt } : obj);

        var metadata = JSON.stringify(updatedMetadata);
        fd.append("metadata", metadata);
        console.log("metadata to update: ", fd);
        $("#processingModal").modal('show');
        updateSeriesMetadata(fd);
    });
});

function toggleAIFeatures() {
    const isNibitySelected = seriesCaptionsDropdown.value === "nibity" || 
        series_captions_label.style.display !== "none";

    if (isNibitySelected) {
        transcriptFeaturesCheckboxes.forEach(checkbox => {
            checkbox.disabled = false;
        });
    } else {
        transcriptFeaturesCheckboxes.forEach(checkbox => {
            checkbox.checked = false;
            checkbox.disabled = true;
        });
        transcriptFeaturesField.value = '';
    }
}

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