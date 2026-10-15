use strict;
use warnings;
use Test::More;
use Data::Dumper ();
use Scalar::Util qw(weaken);

BEGIN {
    eval {
        require Catalyst;
        require Catalyst::Controller;
        require Catalyst::Test;
        require HTTP::Request;
        require HTTP::Body::OctetStream;
        require File::Temp;
        1;
    } or plan skip_all => 'Catalyst request test dependencies are not installed';
}

package Issue1795OwnershipTest;
use Catalyst;
__PACKAGE__->config(name => 'Issue1795OwnershipTest');

package Issue1795OwnershipTest::Controller::Root;
use base 'Catalyst::Controller';
__PACKAGE__->config(namespace => '');
our ($original_body, $original_context, $original_request, $original_temp);

sub dump_body : Local {
    my ($self, $context) = @_;
    my $request = $context->request;
    $request->prepare_body;
    $Issue1795OwnershipTest::original_context = $context;
    Scalar::Util::weaken($Issue1795OwnershipTest::original_context);
    $Issue1795OwnershipTest::original_request = $request;
    Scalar::Util::weaken($Issue1795OwnershipTest::original_request);
    $Issue1795OwnershipTest::original_body = $request->{_body};
    Scalar::Util::weaken($Issue1795OwnershipTest::original_body);
    $Issue1795OwnershipTest::original_temp = $request->{_body}->body;
    Scalar::Util::weaken($Issue1795OwnershipTest::original_temp);

    my $dumper = Data::Dumper->new([$request->{_body}]);
    $dumper->Indent(1);
    $dumper->Purity(1);
    $dumper->Useqq(0);
    $dumper->Quotekeys(1);
    $dumper->Terse(1);
    $dumper->Deepcopy(1);

    $context->res->content_type('text/plain');
    $context->res->body($dumper->Dump);
}

sub index : Local { $_[1]->res->body('ok') }

package Issue1795OwnershipTest;
__PACKAGE__->setup;

package main;
Catalyst::Test->import('Issue1795OwnershipTest');

my $request = HTTP::Request->new('PUT', 'http://localhost/dump_body');
$request->header('Content-Type' => 'application/octet-stream');
$request->header('Content-Length' => 9);
$request->content('foobarbaz');
my $response = request($request);
ok($response && $response->is_success, 'octet-stream request succeeds');
unlike($response->content, qr/\*\{'null'\}/,
    'serialized tempfile handle retains its Perl glob name');

my $body;
{
    no strict 'refs';
    eval '$body = ' . substr($response->content, 8);
}
die $@ if $@;
isa_ok($body, 'HTTP::Body::OctetStream');
isa_ok($body->body, 'File::Temp');
my $path = $body->body->filename;

my $next_response = request(HTTP::Request->new('GET', 'http://localhost/index'));
ok($next_response && $next_response->is_success, 'subsequent request succeeds');
undef $body;
ok(!defined($Issue1795OwnershipTest::original_context),
    'completed request context has no remaining strong owner');
ok(!defined($Issue1795OwnershipTest::original_request),
    'completed Catalyst request has no remaining strong owner');
ok(!defined($Issue1795OwnershipTest::original_body),
    'completed request body has no remaining strong owner');
ok(!defined($Issue1795OwnershipTest::original_temp),
    'completed request tempfile object has no remaining strong owner');
ok(!-e $path, 'request body tempfile is released with the previous request');

done_testing;
