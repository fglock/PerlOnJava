use strict;
use warnings;

use Config;
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

plan skip_all => 'tests PerlOnJava CPAN environment setup'
    unless $Config{archname} =~ /^java-/;

require CPAN;

{
    package Local::CPANFrontend;
    sub optprint { }
}

my $root = tempdir(CLEANUP => 1);
my $older = "$root/Older-Dist";
my $newer = "$root/Newer-Dist";
make_path("$older/blib/lib", "$older/blib/arch", "$older/blib/script");
make_path("$newer/blib/lib", "$newer/blib/arch", "$newer/blib/script");

my $meta = bless {
    is_tested => {
        $older => 100,
        $newer => 200,
    },
}, 'CPAN';

local $ENV{PERL5LIB} = '/existing/perl/lib';
local $ENV{PATH} = '/existing/bin';
local $CPAN::Frontend = bless {}, 'Local::CPANFrontend';

$meta->set_perl5lib('dependency script test');

my @path = split /\Q$Config{path_sep}\E/, $ENV{PATH};
is_deeply(
    [ @path[0, 1] ],
    [ "$newer/blib/script", "$older/blib/script" ],
    'tested dependency scripts are prepended newest first',
);
is($path[2], '/existing/bin', 'the caller PATH remains available');

$meta->set_perl5lib('dependency script test repeated');
@path = split /\Q$Config{path_sep}\E/, $ENV{PATH};
is(
    scalar(grep { $_ eq "$newer/blib/script" } @path),
    1,
    'repeated environment setup does not duplicate script directories',
);

my @perl5lib = split /\Q$Config{path_sep}\E/, $ENV{PERL5LIB};
my %perl5lib_count;
$perl5lib_count{$_}++ for @perl5lib;
is(
    scalar(grep { $perl5lib_count{$_} > 1 } keys %perl5lib_count),
    0,
    'repeated environment setup does not duplicate Perl library directories',
);
is($perl5lib[-1], '/existing/perl/lib', 'the caller PERL5LIB remains available');

done_testing;
