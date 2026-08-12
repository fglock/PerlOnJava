use strict;
use warnings;
use Config;
use Test::More;

plan skip_all => 'tests PerlOnJava CPAN tested-blib prerequisite resolution'
    unless $Config{archname} =~ /^java-/;

require CPAN;
require CPAN::Distribution;

my $dependency = bless { reqtype => 'r' }, 'CPAN::Distribution';
my $command = bless { reqtype => 'c' }, 'CPAN::Distribution';
my $requires = { requires => { 'Runtime::Dependency' => 0 } };
my $build_requires = { build_requires => { 'Build::Dependency' => 0 } };

ok(
    $dependency->_perlonjava_available_file_satisfies_prereq(
        $requires, 'Runtime::Dependency'),
    'a test dependency may satisfy runtime prerequisites from tested blib trees',
);
ok(
    !$command->_perlonjava_available_file_satisfies_prereq(
        $requires, 'Runtime::Dependency'),
    'a top-level command retains the installed runtime prerequisite rule',
);
ok(
    $command->_perlonjava_available_file_satisfies_prereq(
        $build_requires, 'Build::Dependency'),
    'build prerequisites may use tested blib trees',
);

done_testing;
