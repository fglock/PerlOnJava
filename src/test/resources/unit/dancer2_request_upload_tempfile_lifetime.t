use strict;
use warnings;
use Test::More;
use File::Temp;

if (!eval { require Dancer2::Core::Request; 1 }) {
    plan skip_all => 'Dancer2::Core::Request is not installed';
}

my $content = join "\r\n",
    '--BOUNDARY',
    'Content-Disposition: form-data; name="upload"; filename="upload.txt"',
    'Content-Type: text/plain',
    '',
    'upload payload',
    '--BOUNDARY--',
    '';
open my $input, '<', \$content or die "open multipart input: $!";

my $request = Dancer2::Core::Request->new(
    env => {
        'psgi.input'   => $input,
        CONTENT_LENGTH => length($content),
        CONTENT_TYPE   => 'multipart/form-data; boundary=BOUNDARY',
        REQUEST_METHOD => 'POST',
        SCRIPT_NAME    => '/',
        SERVER_PORT    => 80,
    },
);
my $upload = $request->upload('upload');
my $path = $upload->tempname;

my $held_temp = File::Temp->new();
print {$held_temp} 'retained payload';
my $held_temp_path = $held_temp->filename;
close $held_temp;
my $held_upload = Dancer2::Core::Request::Upload->new(
    filename => 'held.txt',
    size     => 16,
    tempname => $held_temp_path,
    headers  => {'Content-Type' => 'text/plain'},
);
$held_upload->{temp_fh} = $held_temp;
$request->uploads->{held} = $held_upload;
undef $held_temp;
undef $held_upload;

ok(-f $path, 'multipart upload tempfile exists while the request lives');
is($upload->content, 'upload payload', 'request upload can read the tempfile');
{
    local our $localized_request_owner = $request;
}
ok(-f $held_temp_path, 'localized request alias preserves upload File::Temp owner');
undef $request;
undef $upload;
ok(!-e $path, 'request destruction removes the upload tempfile');
ok(!-e $held_temp_path, 'request destruction releases the upload File::Temp owner');

done_testing;
