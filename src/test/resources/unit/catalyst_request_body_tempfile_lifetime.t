use strict;
use warnings;
use Test::More;

BEGIN {
    # The regular unit corpus has a small HTTP::Request fixture that omits
    # the API Catalyst::Test needs. Keep this integration check on the real
    # cached CPAN module instead.
    @INC = grep {
        ref($_) || $_ !~ m{(?:^|[/\\])src/test/resources/unit/lib/?\z}
    } @INC;

    my $available = eval {
    require Catalyst;
    require Catalyst::Controller;
    require Catalyst::Test;
    require HTTP::Request;
    die 'HTTP::Request fixture is incomplete' unless HTTP::Request->can('push_header');
    require HTTP::Request::Common;
    1;
    };
    plan skip_all => 'Catalyst test dependencies are not installed' unless $available;
}

package CatalystUploadLifetimeApp;

use Catalyst;
__PACKAGE__->config(name => 'CatalystUploadLifetimeApp');

package CatalystUploadLifetimeApp::Controller::Root;

use base 'Catalyst::Controller';

our $temp_path;

sub body :Path('/body') {
    my ($self, $c) = @_;
    $c->prepare_body;
    $temp_path = $c->req->{_body}->body->filename;
    $c->res->body('ok');
}

sub index :Path('/') {
    $_[1]->res->body('ok');
}

package CatalystUploadLifetimeApp;

__PACKAGE__->setup;

package main;

Catalyst::Test->import('CatalystUploadLifetimeApp');

plan tests => 2;

my $request = HTTP::Request::Common::PUT(
    'http://localhost/body',
    'Content-Type'   => 'application/octet-stream',
    'Content'        => 'payload',
    'Content-Length' => 7,
);
my $response = request($request);
is($response->code, 200, 'octet-stream request reaches the Catalyst app');

my $upload_path = $CatalystUploadLifetimeApp::Controller::Root::temp_path;
request(HTTP::Request::Common::GET('/'));
ok(!-e $upload_path, 'request temp file is removed when the next request releases the old context');
