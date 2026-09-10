use strict;
use warnings;
use Test::More;
use JSON::PP;
use File::Temp qw(tempdir);
use File::Spec;

my $root = File::Spec->rel2abs(File::Spec->catdir(File::Spec->curdir));
my $script = File::Spec->catfile($root, 'dev', 'bench', 'analyze_performance_portfolio.pl');
my $dir = tempdir(CLEANUP => 1);
my $window = sub { { throughput => $_[0] } };
my @names = qw(closure method numeric string regex life json);

sub analyze {
    my ($name, $ratios) = @_;
    my @workloads = map {
        my $ratio = $ratios->{$_};
        { workload => $_, pairs => [ map {
            my $pair_ratio = ref($ratio) eq 'ARRAY' ? $ratio->[$_ - 1] : $ratio;
            { engines => {
                perl => { windows => [$window->(100), $window->(100), $window->(100)] },
                perlonjava => { windows => [$window->(100 * $pair_ratio), $window->(100 * $pair_ratio), $window->(100 * $pair_ratio)] },
            } }
        } 1 .. 7 ] }
    } sort keys %$ratios;
    my $input = File::Spec->catfile($dir, "$name.json");
    open my $fh, '>:raw', $input or die $!;
    print {$fh} JSON::PP->new->encode({
        kind => 'perlonjava-performance-portfolio',
        protocol_compliant => JSON::PP::true,
        conclusive => JSON::PP::true,
        results => \@workloads,
    });
    close $fh or die $!;
    my $raw = qx{$^X $script --input $input --bootstrap 100};
    is($? >> 8, 0, "$name analysis succeeds");
    return JSON::PP->new->decode($raw);
}

my %passing = map { $_ => 1.10 } @names;
ok(analyze('passing', \%passing)->{acceptance}{passed},
    'complete stable portfolio with bounds above one passes');

my %missing = %passing;
delete $missing{json};
is(analyze('missing', \%missing)->{acceptance}{reason}, 'scored workload set is incomplete',
    'missing scored workload cannot pass');

my %anchor_bound = map { $_ => 1.2 } @names;
$anchor_bound{closure} = [.5, .5, .5, 2, 2, 2, 2];
is(analyze('anchor_bound', \%anchor_bound)->{acceptance}{reason},
    'closure or Life confidence interval is not wholly above 1.00x Perl',
    'anchor confidence bound at one cannot pass');

done_testing;
