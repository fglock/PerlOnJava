use strict;
use warnings;
use Test::More;
use PadWalker qw(peek_our);

# File-scope our variables, declared with and without an assignment.
our $file_bare;
our $file_assigned = 17;

my $file_vars = peek_our(0);
is_deeply [sort keys %$file_vars], ['$file_assigned', '$file_bare'],
    'peek_our(0) at file scope lists file-scope our variables';
is ${$file_vars->{'$file_assigned'}}, 17,
    'file-scope our reference reads the package value';
${$file_vars->{'$file_assigned'}} = 18;
is $main::file_assigned, 18,
    'file-scope our reference aliases the package variable';

sub inspect_block_variables {
    {
        our $block_value = 5;
        my $block_vars = peek_our(0);
        is_deeply [sort keys %$block_vars],
            ['$block_value', '$file_assigned', '$file_bare'],
            'peek_our(0) in a bare block sees block and enclosing file-scope our variables';
    }
}
inspect_block_variables();

sub read_package_variables {
    our $sub_assigned = 'sub';
    our $sub_bare;
    return peek_our(0);
}

my $sub_vars = read_package_variables();
is_deeply [sort keys %$sub_vars],
    ['$file_assigned', '$file_bare', '$sub_assigned', '$sub_bare'],
    'peek_our(0) in a sub lists its own and enclosing file-scope our variables';
is ${$sub_vars->{'$sub_assigned'}}, 'sub',
    'sub-scope our reference reads the assigned package value';

sub no_package_variables {
    my $local = 1;
    return peek_our(0);
}

my $plain_vars = no_package_variables();
is_deeply [sort keys %$plain_vars], ['$file_assigned', '$file_bare'],
    'peek_our(0) in a sub without our declarations does not report @_';

sub caller_level {
    return peek_our(1);
}

sub call_caller_level {
    our $middle_value = 'middle';
    return caller_level();
}

my $level_vars = call_caller_level();
is_deeply [sort keys %$level_vars],
    ['$file_assigned', '$file_bare', '$middle_value'],
    'peek_our(1) reports the calling sub frame';

done_testing;
