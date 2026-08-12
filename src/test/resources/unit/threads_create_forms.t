use strict;
use warnings;
use threads;

print "1..4\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

sub named_entry { return $_[0] + 1 }

my $with_options = threads->create({ stack_size => 0 }, sub { return 7 });
check($with_options->join == 7, 'create accepts an options hash before CODE');
check(!$with_options->error, 'options-hash thread has no error');

my $by_name = threads->create('named_entry', 8);
check($by_name->join == 9, 'create resolves a named entry subroutine');
check(!$by_name->error, 'named-entry thread has no error');
