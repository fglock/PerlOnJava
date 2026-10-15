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
our ($original_body, $original_context, $original_request, $original_temp);
__PACKAGE__->config(name => 'Issue1795OwnershipTest');

package Issue1795OwnershipTest::Controller::Root;
use base 'Catalyst::Controller';
__PACKAGE__->config(namespace => '');

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

# The unit-test HTTP::Request shim only implements the request fields these
# tests need. Add the small HTTP::Message surface used by Catalyst::Test when
# that shim is active; installed HTTP::Request distributions already provide
# these methods.
{
    no strict 'refs';
    no warnings qw(once redefine);
    *HTTP::Request::method = sub { $_[0]{method} }
        unless HTTP::Request->can('method');
    *HTTP::Request::headers = sub {
        bless {request => $_[0]}, 'HTTP::Request::_Headers';
    } unless HTTP::Request->can('headers');
    *HTTP::Request::protocol = sub { 'HTTP/1.1' }
        unless HTTP::Request->can('protocol');
    *HTTP::Request::content = sub {
        my ($self, $content) = @_;
        $self->{content} = $content if @_ > 1;
        return $self->{content} // '';
    } unless HTTP::Request->can('content');
    *HTTP::Request::content_length = sub {
        $_[0]->header('Content-Length');
    } unless HTTP::Request->can('content_length');
    *HTTP::Request::_URI::clone = sub {
        my %copy = %{$_[0]};
        return bless \%copy, ref($_[0]);
    } unless HTTP::Request::_URI->can('clone');
    *HTTP::Request::_URI::query = sub { $_[0]{query} }
        unless HTTP::Request::_URI->can('query');
    *HTTP::Request::_URI::path_query = sub { $_[0]{path} }
        unless HTTP::Request::_URI->can('path_query');
    *HTTP::Request::_URI::host_port = sub {
        $_[0]{host} . ':' . $_[0]{port};
    } unless HTTP::Request::_URI->can('host_port');
    *HTTP::Request::_URI::default_port = sub {
        $_[0]{scheme} eq 'https' ? 443 : 80;
    } unless HTTP::Request::_URI->can('default_port');
}

{
    no strict 'refs';
    no warnings qw(once redefine);
    *HTTP::Request::_Headers::header_field_names = sub {
        my ($self) = @_;
        return map {
            my $name = $_;
            $name =~ s/(^|-)([a-z])/$1 . uc($2)/ge;
            $name;
        } keys %{$self->{request}{headers}};
    } unless HTTP::Request::_Headers->can('header_field_names');
    *HTTP::Request::_Headers::header = sub {
        my ($self, @args) = @_;
        return $self->{request}->header(@args);
    } unless HTTP::Request::_Headers->can('header');
}

my $request = HTTP::Request->new(
    'PUT', 'http://localhost/dump_body',
);
$request->header('Content-Type' => 'application/octet-stream');
$request->header('Content-Length' => 9);
$request->content('foobarbaz');
my $response = request($request);
ok($response && $response->is_success, 'octet-stream request succeeds');
unlike($response->content, qr/\*\{'null'\}/,
    'serialized tempfile handle retains its Perl glob name');
unlike($response->content,
    qr/^\# \*\{'File::Temp::\$fh'\} = \[\];$/m,
    'serialization does not invent an ARRAY slot on the lexical tempfile glob');

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
