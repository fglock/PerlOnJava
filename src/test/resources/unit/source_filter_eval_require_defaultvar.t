use strict;
use warnings;
use Test::More tests => 2;
use File::Spec;
use File::Temp qw(tempdir);

{
    package EvalRequireDefaultVarFilter;
    use Filter::Util::Call;

    sub import {
        my $done = 0;
        filter_add(sub {
            my $status = filter_read();
            return $status unless $status > 0;
            $_ = "sub loaded { 1 }\n$_" unless $done++;
            return $status;
        });
    }
}
$INC{'EvalRequireDefaultVarFilter.pm'} = __FILE__;

my $tmp = tempdir(CLEANUP => 1);
my $pm = File::Spec->catfile($tmp, 'EvalRequireDefaultVarTarget.pm');
open my $fh, '>', $pm or die "open $pm: $!";
print {$fh} <<'EOPM';
package EvalRequireDefaultVarTarget;
use EvalRequireDefaultVarFilter;
1;
EOPM
close $fh;

local @INC = ($tmp, @INC);

my @seen;
my @items = ('EvalRequireDefaultVarTarget');
my $count = grep {
    push @seen, "before=$_";
    eval "require $_";
    die $@ if $@;
    push @seen, "after=$_";
    $_->can('loaded');
} @items;

is($count, 1, 'eval-string require loads source-filtered module inside grep');
is_deeply(
    \@seen,
    [
        'before=EvalRequireDefaultVarTarget',
        'after=EvalRequireDefaultVarTarget',
    ],
    'source filtering preserves grep default variable alias',
);
