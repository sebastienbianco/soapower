function EnvironmentsCtrl($scope, $rootScope, $routeParams, EnvironmentsService, UIService) {

    // Looking for environments with their groups and adding all informations to $scope.environments var
    EnvironmentsService.findAll($routeParams.group).
        success(function (environments) {
            $scope.environments = environments.data;
        })
        .error(function (resp) {
            console.log("Error with EnvironmentsService.findAll" + resp);
        });

    $rootScope.$broadcast("showGroupsFilter", $routeParams.group);

    $scope.$on("ReloadPage", function (event, group) {
        $scope.ctrlPath = "environments";
        UIService.reloadAdminPage($scope, group);
    });
}

function EnvironmentEditCtrl($scope, $routeParams, $location, Environment, UIService, GroupsService) {
    var self = this;

    Environment.get({environmentId: $routeParams.environmentId}, function (environment) {
        self.original = environment;
        $scope.environment = new Environment(self.original);
        $scope.environment.recordXmlData = UIService.fixBooleanReverse($scope.environment.recordXmlData);
        $scope.environment.recordData = UIService.fixBooleanReverse($scope.environment.recordData);
        GroupsService.findAllAndSelect($scope, null, null, $scope.environment, false);
    });

    $scope.isClean = function () {
        return angular.equals(self.original, $scope.environment);
    }

    $scope.destroy = function () {
        self.original.destroy(function () {
            $location.path('/environments');
        });
    };

    $scope.save = function () {
        $scope.environment.update(function () {
            $location.path('/environments');
        }, function (response) { // error case
            alert(response.data);
        });
    };
}

function EnvironmentNewCtrl($scope, $location, Environment, GroupsService) {

    GroupsService.findAllAndSelect($scope, null, null, null, false);

    $scope.environment = new Environment({id: '-1'});
    $scope.environment.hourRecordXmlDataMin = 6;
    $scope.environment.hourRecordXmlDataMax = 22;
    $scope.environment.nbDayKeepXmlData = 2;
    $scope.environment.nbDayKeepAllData = 4;
    $scope.environment.recordXmlData = "yes";
    $scope.environment.recordData = "yes";

    $scope.save = function () {
        $scope.environment.update(function () {
            $location.path('/environments/');
        }, function (response) { // error case
            alert(response.data);
        });
    }
}