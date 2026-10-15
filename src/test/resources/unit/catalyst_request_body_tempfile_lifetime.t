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
our ($original_body, $original_context, $original_request, $original_temp,
     $tempfile_path, $tempfile_exists_during_request);
our ($multipart_context, $multipart_request, $multipart_body,
     $multipart_tempfile_paths, $multipart_tempfiles_exist_during_request);
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
    my $tempfile = $request->{_body}->body;
    $Issue1795OwnershipTest::original_temp = $tempfile;
    Scalar::Util::weaken($Issue1795OwnershipTest::original_temp);
    $Issue1795OwnershipTest::tempfile_path = $tempfile->filename;
    $Issue1795OwnershipTest::tempfile_exists_during_request =
        -e $Issue1795OwnershipTest::tempfile_path;

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

sub multipart_upload : Local {
    my ($self, $context) = @_;
    my $request = $context->request;
    $request->prepare_body;
    $request->prepare_body_parameters($context);
    my @uploads = $request->upload('attachment');

    $Issue1795OwnershipTest::multipart_context = $context;
    Scalar::Util::weaken($Issue1795OwnershipTest::multipart_context);
    $Issue1795OwnershipTest::multipart_request = $request;
    Scalar::Util::weaken($Issue1795OwnershipTest::multipart_request);
    $Issue1795OwnershipTest::multipart_body = $request->{_body};
    Scalar::Util::weaken($Issue1795OwnershipTest::multipart_body);
    $Issue1795OwnershipTest::multipart_tempfile_paths =
        [map { $_->tempname } @uploads];
    $Issue1795OwnershipTest::multipart_tempfiles_exist_during_request =
        [map { -e $_->tempname } @uploads];

    my $body = delete $request->{_body};
    my $request_context = delete $request->{_context};
    my $request_env = delete $request->{env};
    my $dumper = Data::Dumper->new([$request]);
    $dumper->Indent(1);
    $dumper->Purity(1);
    $dumper->Useqq(0);
    $dumper->Quotekeys(1);
    $dumper->Terse(1);
    $dumper->Deepcopy(1);
    my $serialized = $dumper->Dump;
    $request->{_context} = $request_context;
    Scalar::Util::weaken($request->{_context});
    $request->{_body} = $body;
    $request->{env} = $request_env;
    $context->res->body($serialized);
}

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
ok($Issue1795OwnershipTest::tempfile_exists_during_request,
    'request body tempfile remains available while handling the upload');
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
is($path, $Issue1795OwnershipTest::tempfile_path,
    'serialized body refers to the upload tempfile');

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

my $boundary = '----perlonjava-upload-boundary';
my $multipart_content = '';
for my $index (1 .. 3) {
    $multipart_content .= join "\r\n",
        "--$boundary",
        "Content-Disposition: form-data; name=\"attachment\"; filename=\"note$index.txt\"",
        'Content-Type: text/plain',
        '',
        "upload payload $index",
        '';
}
$multipart_content .= "--$boundary--\r\n";
my $upload_request = HTTP::Request->new(
    'POST', 'http://localhost/multipart_upload',
);
$upload_request->header(
    'Content-Type' => "multipart/form-data; boundary=$boundary");
$upload_request->header('Content-Length' => length($multipart_content));
$upload_request->content($multipart_content);
my $multipart_response = request($upload_request);
ok($multipart_response && $multipart_response->is_success,
    'multipart upload request succeeds');
my $decoded_request;
{
    no strict 'refs';
    eval '$decoded_request = ' . $multipart_response->content;
}
die $@ if $@;
isa_ok($decoded_request, 'Catalyst::Request');
my @decoded_uploads = $decoded_request->upload('attachment');
is(scalar @decoded_uploads, 3, 'all multipart uploads are present');
isa_ok($decoded_uploads[0], 'Catalyst::Request::Upload');
my @multipart_paths = map { $_->tempname } @decoded_uploads;
is_deeply(\@multipart_paths,
    $Issue1795OwnershipTest::multipart_tempfile_paths,
    'serialized uploads retain their tempfile paths');
ok(!grep { !$_ } @{$Issue1795OwnershipTest::multipart_tempfiles_exist_during_request},
    'all multipart tempfiles remain available while handling the upload');
my $after_upload_response = request(
    HTTP::Request->new('GET', 'http://localhost/index'));
ok($after_upload_response && $after_upload_response->is_success,
    'request after multipart upload succeeds');
ok(!defined($Issue1795OwnershipTest::multipart_context),
    'completed multipart request context has no remaining strong owner');
ok(!defined($Issue1795OwnershipTest::multipart_request),
    'completed multipart Catalyst request has no remaining strong owner');
ok(!defined($Issue1795OwnershipTest::multipart_body),
    'completed multipart request body has no remaining strong owner');
ok(!grep { -e $_ } @multipart_paths,
    'all multipart upload tempfiles are removed after the request completes');

done_testing;
