use strict;
use warnings;
use Test::More tests => 10;
use File::Temp qw(tempfile tempdir);
use File::Spec;
use IO::Handle;

{
    package Local::CookieURI;
    sub new { bless {}, shift }
    sub host { 'localhost' }
    sub port { 80 }
    sub path { '/' }

    package Local::CookieRequest;
    sub new { bless { uri => Local::CookieURI->new }, shift }
    sub uri { $_[0]{uri} }
    sub header { undef }

    package Local::CookieResponse;
    sub new {
        bless {
            request => Local::CookieRequest->new,
            headers => { 'Set-Cookie' => 'ID=123; path=/' },
        }, shift;
    }
    sub request { $_[0]{request} }
    sub header { $_[0]{headers}{$_[1]} }
    sub _header { $_[0]{headers}{$_[1]} }
}

package main;

my $dir = tempdir(CLEANUP => 1);
my $path = "$dir/file.txt";
open my $out, '>', $path or die $!;
print {$out} 'abc';
close $out;

ok(-f $path, 'plain regular file passes -f');
ok(!-f "$path/", 'regular file with trailing separator fails -f');

my ($fh, $unlinked_path) = tempfile(UNLINK => 0);
print {$fh} 'abc';
$fh->flush;
unlink $unlinked_path or die $!;
is(-s $fh, 3, '-s uses the open filehandle after its path is unlinked');

my $buffer = 'line';
open my $scalar_fh, '<', \$buffer or die $!;
ok($scalar_fh->can('getline'), 'scalar-backed filehandle can getline');
ok(*{$scalar_fh}{IO}->can('getline'), 'IO slot object can getline');

use HTTP::Cookies;
my $response = Local::CookieResponse->new;
my $jar = HTTP::Cookies->new;
is($jar->extract_cookies($response), $response, 'extract_cookies returns the response');
my @cookies;
$jar->scan(sub { @cookies = @_ });
is($cookies[1], 'ID', 'extract_cookies stores a response cookie');

my @die_messages;
{
    local $SIG{__DIE__} = sub {
        push @die_messages, $_[0];
        die @_;
    };
    eval {
        eval { die 'plack rethrow' };
        die $@;
    };
}
like($die_messages[0], qr/^plack rethrow at .* line \d+\.\n\z/,
    '__DIE__ receives the formatted string exception');
is($die_messages[1], $die_messages[0],
    'rethrow preserves the original formatted exception');

my $cleanup_file;
{
    my $cleanup_dir = File::Temp->newdir('plack-cleanup-XXXXX',
        TMPDIR => 1, CLEANUP => 1);
    $cleanup_file = File::Spec->catfile($cleanup_dir, 'upload');
    open my $cleanup_fh, '>', $cleanup_file or die $!;
    close $cleanup_fh;
    File::Spec->catfile($cleanup_dir, "part-$_") for 1 .. 3;
}
ok(!-e $cleanup_file,
    'File::Spec temporaries do not retain a File::Temp cleanup directory');
