const urlParams = new URLSearchParams(window.location.search);
const seriesID = urlParams.get('sid');

$(document).ready(function(){

    getSeriesInfo();

    $("#settingsForm").submit(function(e){
      e.preventDefault();
      var captions = $('#series_captions').val();
      var fd;
      fd = new FormData();
      var metadata = {"fields":[{"id":"caption-type","label":"Captions","type":"text","value":captions}]};
      var captions_data = JSON.stringify(metadata);

      fd.append("type", "ext/series");
      fd.append("metadata",captions_data);
      var url = "/api/series/" + seriesID + "/metadata";

      $.ajax({
          type: "PUT",
          url: url,
          processData: false,
          contentType: false,
          data: fd,
          dataType: "json"
      }).done(function (data) {
          console.log(data);
      }).fail(function (error) {
          console.log('FAIL');
      });
 });
});

function getSeriesInfo() {
    var ext_url = "/api/series/" + seriesID + "/metadata?type=ext/series",
    dublincore_url = "/api/series/" + seriesID + "/metadata?type=dublincore/series";

    $.get({url: dublincore_url, responseType: 'json'},
       function(result) {
          for (var j=0; j < result.length; j++) {
              if(result[j].id == "title") {
                $('#series_title').val(result[j].value);
              }
              if(result[j].id == "createdBy") {
                $('#series_creator').val(result[j].value);
              }
          }
       }
    )

    $.get({url: ext_url, responseType: 'json'},
        function(response) {
           for (var i = 0; i<response.length;i++){
              if(response[i].id == "retention-cycle") {
                  if(response[i].value == "normal") {
                   $('#series_retention  option[value=normal]').attr('selected','selected');
                  } else if(response[i].value == "long") {
                   $('#series_retention  option[value=long]').attr('selected','selected');
                  } else if(response[i].value == "forever") {
                   $('#series_retention  option[value=forever]').attr('selected','selected');
                  }
              }
              if(response[i].id == "caption-type") {
                 if(response[i].value == "none") {
                    $('#series_captions  option[value=none]').attr('selected','selected');
                 } else if(response[i].value == "google") {
                    $('#series_captions  option[value=google]').attr('selected','selected');
                 } else if(response[i].value == "") {
                    $('#series_captions  option[value=no_selection]').attr('selected','selected');
                 }
              }
              if(response[i].id == "series-locked") {
                if(response[i].value == false) {
                  $('#locked_status').val("No");
                } else {
                  $('#locked_status').val("Yes");
                }
              }
              if(response[i].id == "series-expiry-date") {
                 if(response[i].value != '') {
                   $('#retain_date').val(response[i].value);
                 } else {
                   $('#retain_date').val('');
                 }
              }
           }
        }
    )
}