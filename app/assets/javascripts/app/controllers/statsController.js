function StatsCtrl($scope, $rootScope, $http, $location, $routeParams, $filter, ngTableParams, UIService) {
    $scope.ctrlPath = "stats";

    $scope.showTips = false;
    $scope.hostname = $location.host();
    $scope.port = $location.port();

    var groups = $routeParams.groups ? $routeParams.groups : 'all';
    var environment = $routeParams.environment ? $routeParams.environment : 'all';
    var mindate = $routeParams.mindate ? $routeParams.mindate : 'all';
    var maxdate = $routeParams.maxdate ? $routeParams.maxdate : 'all';
    var code = $routeParams.code ? $routeParams.code : 'all';
    var url = $scope.ctrlPath +
        '/' + groups +
        '/' + environment +
        '/' + mindate +
        '/' + maxdate +
        '/' + code +
        '/listDatatable?' +
        'sSearch=' +
        '&iDisplayStart=' + 0 +
        '&iDisplayLength=' + 10000 +
        '&call=' + new Date();

    $http({ method: 'GET', url: url, cache: false })
        .success(function (dataJson) {
            $scope.data = dataJson.data;

            $scope.tableParams = new ngTableParams({
                page: 1,            // show first page
                count: 10,          // count per page
                sorting: {
                    'name': 'asc'     // initial sorting
                }
            }, {
                total: $scope.data.length, // length of data
                getData: function ($defer, params) {
                    var datafilter = $filter('customAndSearch');
                    var requestsData = datafilter($scope.data, $scope.tableFilter);
                    var orderedData = params.sorting() ? $filter('orderBy')(requestsData, params.orderBy()) : requestsData;
                    var res = orderedData.slice((params.page() - 1) * params.count(), params.page() * params.count());
                    params.total(orderedData.length)
                    $defer.resolve(res);
                },
                $scope: { $data: {} }
            });

            $scope.$watch("tableFilter", function () {
                $scope.tableParams.reload()
            });

        });


    $scope.$on("ReloadPage", function (event) {
        UIService.reloadPage($scope, false);
    });
}