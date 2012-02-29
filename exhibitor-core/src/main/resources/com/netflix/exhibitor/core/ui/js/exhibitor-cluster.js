var baseServerItem = null;
var currentServersSpec = null;
var currentHostname = null;
var serverItemsVersion = 0;

var STATE_LATENT = 0;
var STATE_DOWN = 1;
var STATE_NOT_SERVING = 2;
var STATE_SERVING = 3;
var STATE_DOWN_BECAUSE_UNLISTED = 4;
var STATE_DOWN_BECAUSE_RESTARTS_TURNED_OFF = 5;

function makeServersList()
{
    var serverList = new Array();

    var specs = (systemConfig.serversSpec.length > 0) ? systemConfig.serversSpec.split(",") : new Array();
    var foundUs = false;
    for ( var i = 0; i < specs.length; ++i )
    {
        var parts = specs[i].split(":");

        var thisSpec = {};
        thisSpec.serverId = parseInt(parts[0]);
        thisSpec.hostname = parts[1];
        if ( thisSpec.hostname === systemConfig.hostname )
        {
            foundUs = true;
        }

        serverList.push(thisSpec);
    }
    if ( !foundUs )
    {
        thisSpec = {};
        thisSpec.serverId = -1;
        thisSpec.hostname = systemConfig.hostname;
        serverList.push(thisSpec);
    }

    serverList.sort(function(a, b){
        return a.serverId - b.serverId;
    });

    return serverList;
}

function buildServerItems()
{
    if ( baseServerItem == null )
    {
        baseServerItem = $('#base-server-item').html();
    }

    var serversList = makeServersList();
    if ( (currentServersSpec != systemConfig.serversSpec) || (currentHostname != systemConfig.hostname) )
    {
        ++serverItemsVersion;
        internalBuildServerItems(serversList);
    }
    updateServerState(serversList);
}

function internalBuildServerItems(serversList)
{
    currentServersSpec = systemConfig.serversSpec;
    currentHostname = systemConfig.hostname;

    var content = "";
    for ( var i = 0; i < serversList.length; ++i )
    {
        var spec = serversList[i];
        var domId = 'cp-' + i;

        var thisItem = baseServerItem;
        thisItem = thisItem.replace("$SERVER_ID$", spec.serverId);
        thisItem = thisItem.replace("$SERVER_HOSTNAME$", spec.hostname);
        thisItem = thisItem.replace(/\$BASE_ID\$/gi, domId);

        content += thisItem;
    }
    $('#base-server-item').html(content);

    for ( i = 0; i < serversList.length; ++i )
    {
        spec = serversList[i];
        domId = '#cp-' + i;

        makeLightSwitch(domId + '-instance-restarts-enabled', null, true);
        makeLightSwitch(domId + '-cleanup-enabled', null, true);
        makeLightSwitch(domId + '-unlisted-restarts', null, true);
        makeLightSwitch(domId + '-backups-enabled', null, true);
        if ( systemState.backupActive )
        {
            $(domId + '-backups-enabled-control').show();
        }

        var serverIdContent = "Server Id: " + spec.serverId;
        var hostnameContent = "Hostname: " + spec.hostname;
        if ( spec.hostname === systemConfig.hostname )
        {
            hostnameContent += '<span class="cp-this-server">(This server)</span>';
            $(domId + '-log-button').hide();
        }

        $(domId + '-server-id').html(serverIdContent);
        $(domId + '-hostname').html(hostnameContent);

        $(domId + '-power-button').button({
            disabled: true,
            icons:{
                primary:"ui-icon-alert"
            }
        }).click(stopStartDialog(spec.hostname));

        $(domId + '-4ltr-button').button({
            disabled: true,
            icons:{
                primary:"ui-icon-circle-zoomin"
            }
        }).click(word4ltrDialog(spec.hostname));

        $(domId + '-log-button').button({
            disabled: true,
            icons:{
                primary:"ui-icon-info"
            }
        }).click(logDialog(spec.hostname));
    }

    $('#base-server-item').colorTip();
}

function stopStartDialog(hostname)
{
    return function() {
        okCancelDialog(hostname, "Are you sure you want to restart this server?", function(){
            makeRemoteCall("cluster/restart/", hostname);
        });
    };
}

function logDialog(hostname)
{
    return function() {
        makeRemoteCall("cluster/log/", hostname, function(text){
            $('#log-text').text(text);
            $('#log-dialog').dialog("option", "title", hostname);
            $('#log-dialog').dialog("open");
        });
    };
}

function word4ltrDialog(hostname)
{
    return function() {
        $('#word-4ltr-button').click(function(){
            makeRemoteCall("cluster/4ltr/" + $('#word-4ltr').val() + "/", hostname, function(text){
                $('#word-4ltr-text').text(text)
            })
        });
        $('#word-4ltr-dialog').dialog("option", "title", hostname);
        $('#word-4ltr-dialog').dialog("open");
    };
}

function makeRemoteCall(baseUrl, hostname, callback)
{
    $.getJSON(baseUrl + hostname, function(data){
        if ( data.success )
        {
            if ( callback )
            {
                callback(data.response);
            }
        }
        else
        {
            messageDialog("Error", "Could not complete message to " + hostname + ". Message: " + data.errorMessage);
        }
    });
}

function handleSwitch(index, hostname, type)
{
    return function(isChecked) {
        makeRemoteCall('cluster/set/' + type + "/" + isChecked + "/", hostname);
    };
}

function updateOneServerState(index, data, hostname)
{
    var statusColor;
    var statusMessage = "";

    domId = '#cp-' + index;
    if ( data.success )
    {
        var isRunning = ((data.response.state == STATE_SERVING) || (data.response.state == STATE_NOT_SERVING));

        $(domId + '-power-button').button("option", "disabled", false);
        $(domId + '-4ltr-button').button("option", "disabled", !isRunning);
        $(domId + '-log-button').button("option", "disabled", !isRunning);

        ableLightSwitch(domId + '-instance-restarts-enabled', handleSwitch(index, hostname, "restarts"));
        ableLightSwitch(domId + '-cleanup-enabled', handleSwitch(index, hostname, "cleanup"));
        ableLightSwitch(domId + '-unlisted-restarts', handleSwitch(index, hostname, "unlistedRestarts"));
        ableLightSwitch(domId + '-backups-enabled', handleSwitch(index, hostname, "backups"));

        checkLightSwitch(domId + '-instance-restarts-enabled', data.response.switches.restarts);
        checkLightSwitch(domId + '-cleanup-enabled', data.response.switches.cleanup);
        checkLightSwitch(domId + '-unlisted-restarts', data.response.switches.unlistedRestarts);
        checkLightSwitch(domId + '-backups-enabled', data.response.switches.backups);

        statusMessage = data.response.description;
        switch ( data.response.state )
        {
            default:
            case STATE_LATENT:
            {
                statusColor = "#FFF";
                break;
            }

            case STATE_SERVING:
            {
                statusColor = "#0E0";
                break;
            }

            case STATE_NOT_SERVING:
            case STATE_DOWN_BECAUSE_UNLISTED:
            case STATE_DOWN_BECAUSE_RESTARTS_TURNED_OFF:
            {
                statusColor = "#FF0";
                break;
            }

            case STATE_DOWN:
            {
                statusColor = "#F00";
                break;
            }
        }
    }
    else
    {
        $(domId + '-power-button').button("option", "disabled", true);
        $(domId + '-4ltr-button').button("option", "disabled", true);
        $(domId + '-log-button').button("option", "disabled", true);

        ableLightSwitch(domId + '-instance-restarts-enabled', null, false);
        ableLightSwitch(domId + '-cleanup-enabled', null, false);
        ableLightSwitch(domId + '-unlisted-restarts', null, false);
        ableLightSwitch(domId + '-backups-enabled', null, false);

        statusColor = "#F00";
        statusMessage = data.errorMessage;
    }
    $(domId + '-status-indicator').css('background-color', statusColor);
    $(domId + '-status-message').html("Status: " + statusMessage);
}

function updateServerState(serversList)
{
    var localServerItemsVersion = serverItemsVersion;
    for ( var i = 0; i < serversList.length; ++i )
    {
        var spec = serversList[i];
        var thisHostname = spec.hostname;
        if ( thisHostname === systemConfig.hostname )
        {
            thisHostname = "localhost";
        }
        var callback = function(index, hostname) {
            return function(data) {
                if ( serverItemsVersion === localServerItemsVersion )
                {
                    updateOneServerState(index, data, hostname);
                }
            };
        };
        $.getJSON('cluster/state/' + thisHostname, callback(i, thisHostname));
    }
}
