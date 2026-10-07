use strict;
use warnings;
use CPAN::Meta::YAML;
use File::Spec;
use FindBin;
use Test::More tests => 2;
my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $workflow = shift @ARGV // File::Spec->catfile($root, '.github', 'workflows', 'gradle.yml');
my $documents = CPAN::Meta::YAML->read($workflow);
my ($build) = grep { ($_->{id} // '') eq 'build-windows' }
    @{$documents->[0]{jobs}{build}{steps}};
cmp_ok($build->{'timeout-minutes'}, '>=', 75,
    'Windows build budget accommodates the complete serial unit gate');
is($build->{run}, 'make ci', 'longer gate retains the complete Make CI target');
