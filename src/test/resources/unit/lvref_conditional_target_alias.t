use v5.22;
use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Test::More;

my ($left, $right) = (1, 2);

{
    use warnings 'experimental::refaliasing';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, shift };
    our ($warning_source, $warning_target);
    eval q{\$warning_target = \$warning_source};
    eval q{\($warning_target) = \$warning_source};
    is scalar @warnings, 2, 'direct and parenthesized ref aliases each warn once';
    like $warnings[0], qr/^Aliasing via reference is experimental/,
        'direct ref alias warning is experimental';
    like $warnings[1], qr/^Aliasing via reference is experimental/,
        'parenthesized ref alias warning is experimental';
}

our $parenthesized_package_target;
my $parenthesized_source = 7;
@_ = \$parenthesized_source;
(\$parenthesized_package_target) = @_;
is \$parenthesized_package_target, \$parenthesized_source,
    'one-member parenthesized package target consumes a reference list';

my $parenthesized_lexical_target;
(\$parenthesized_lexical_target) = @_;
is \$parenthesized_lexical_target, \$parenthesized_source,
    'one-member parenthesized lexical target consumes a reference list';

(\my $parenthesized_declared_target) = @_;
is \$parenthesized_declared_target, \$parenthesized_source,
    'one-member parenthesized declared target consumes a reference list';

our @parenthesized_local_target;
my @parenthesized_local_source = qw(local source);
my $parenthesized_local_original = \@parenthesized_local_target;
{
    (\local @parenthesized_local_target) = \@parenthesized_local_source;
    is \@parenthesized_local_target, \@parenthesized_local_source,
        'one-member parenthesized local array target aliases the source array';
}
is \@parenthesized_local_target, $parenthesized_local_original,
    'one-member parenthesized local array target unwinds its localization';

my %parenthesized_hash_source = (answer => 42);
(\my %parenthesized_hash_target) = \%parenthesized_hash_source;
is \%parenthesized_hash_target, \%parenthesized_hash_source,
    'one-member parenthesized declared hash target aliases the source hash';

our %parenthesized_local_hash_target;
my %parenthesized_local_hash_source = (local => 'hash');
my $parenthesized_local_hash_original = \%parenthesized_local_hash_target;
{
    (\local %parenthesized_local_hash_target) = \%parenthesized_local_hash_source;
    is \%parenthesized_local_hash_target, \%parenthesized_local_hash_source,
        'one-member parenthesized local hash target aliases the source hash';
}
is \%parenthesized_local_hash_target, $parenthesized_local_hash_original,
    'one-member parenthesized local hash target unwinds its localization';

{
    use feature 'lexical_subs';
    no warnings 'experimental::lexical_subs';
    sub lexical_sub_source { 'source' }
    my sub lexical_sub_target;
    \&lexical_sub_target = \&lexical_sub_source;
    is \&lexical_sub_target, \&lexical_sub_source,
        'direct lexical sub target aliases the source code reference';
    my sub parenthesized_lexical_sub_target;
    (\&parenthesized_lexical_sub_target) = (\&lexical_sub_source);
    is \&parenthesized_lexical_sub_target, \&lexical_sub_source,
        'parenthesized lexical sub target aliases the source code reference';

    my sub foreach_lexical_sub;
    my @foreach_lexical_sub_results;
    for \&foreach_lexical_sub(sub { 'first' }, sub { 'second' }) {
        push @foreach_lexical_sub_results, &foreach_lexical_sub;
    }
    is_deeply \@foreach_lexical_sub_results, [qw(first second)],
        'foreach lexical sub target aliases each source code reference';
}

my @foreach_declared_array_results;
for \my @foreach_declared_array([qw(one two)], [qw(three four)]) {
    push @foreach_declared_array_results, @foreach_declared_array;
}
is_deeply \@foreach_declared_array_results, [qw(one two three four)],
    'foreach declared array target dereferences each array reference';

our @foreach_shadowed_array = qw(package values);
my @foreach_shadowed_array_results;
for \my @foreach_shadowed_array([qw(one two)], [qw(three four)]) {
    push @foreach_shadowed_array_results, @foreach_shadowed_array;
}
is_deeply \@foreach_shadowed_array_results, [qw(one two three four)],
    'foreach declared array target shadows a same-named package array';

{
    no strict 'vars';
    my @foreach_implicit_package_source = qw(package values);
    \@foreach_implicit_package_array = \@foreach_implicit_package_source;
    my @foreach_implicit_package_results;
    for \my @foreach_implicit_package_array([qw(one two)], [qw(three four)]) {
        push @foreach_implicit_package_results, @foreach_implicit_package_array;
    }
    is_deeply \@foreach_implicit_package_results, [qw(one two three four)],
        'foreach declared array target shadows an implicitly created package array';
}

my @foreach_declared_hash_results;
for \my %foreach_declared_hash({ first => 1 }, { second => 2 }) {
    push @foreach_declared_hash_results, %foreach_declared_hash;
}
is_deeply [sort @foreach_declared_hash_results], [qw(1 2 first second)],
    'foreach declared hash target dereferences each hash reference';

{
    no warnings 'redefine';
    my @foreach_package_sub_results;
    for \&foreach_package_sub(sub { 'first' }, sub { 'second' }) {
        push @foreach_package_sub_results, &foreach_package_sub;
    }
is_deeply \@foreach_package_sub_results, [qw(first second)],
        'foreach package sub target aliases each source code reference';
}

{
    my $error;
    eval { my $target; \$target = 3 };
    $error = $@;
    like $error, qr/^Assigned value is not a reference at/,
        'scalar ref alias rejects a non-reference with Perl diagnostic';
    eval { my @target; \@target = {} };
    $error = $@;
    like $error, qr/^Assigned value is not an ARRAY reference at/,
        'array ref alias rejects the wrong reference type with Perl diagnostic';
    eval { my %target; \%target = [] };
    $error = $@;
    like $error, qr/^Assigned value is not a HASH reference at/,
        'hash ref alias rejects the wrong reference type with Perl diagnostic';
    eval { my $target; \$target = [] };
    $error = $@;
    like $error, qr/^Assigned value is not a SCALAR reference at/,
        'scalar ref alias rejects the wrong reference type with Perl diagnostic';
    eval { my sub target; \&target = [] };
    $error = $@;
    like $error, qr/^Assigned value is not a CODE reference at/,
        'code ref alias rejects the wrong reference type with Perl diagnostic';

    our (@alias_local, %alias_hash);
    for my $case (
        [ '(\\do{}) = 42', qr/^Can't modify reference to do block in list assignment at/ ],
        [ '(\\pos) = 42', qr/^Can't modify reference to match position in list assignment at/ ],
        [ '(\\glob) = 42', qr/^Can't modify reference to glob in list assignment at/ ],
        [ '\\(local @alias_local) = 42', qr/^Can't modify reference to localized parenthesized array in list assignment at/ ],
        [ '\\(%alias_hash) = 42', qr/^Can't modify reference to parenthesized hash in list assignment at/ ],
        [ '\\%{"42"} = 42', qr/^Can't modify reference to hash dereference in scalar assignment at/ ],
        [ '\\$0 =~ y/// = 0', qr/^Can't modify transliteration \(tr\/\/\/\) in scalar assignment at/ ],
    ) {
        eval $case->[0];
        like $@, $case->[1], "invalid ref alias target: $case->[0]";
    }
}

my $choose_left = 1;
$choose_left ? \$left : $right = \3;
is $left, 3, 'conditional target aliases an explicitly referenced true branch';
is $right, 2, 'conditional target leaves the unselected false branch alone';

$choose_left = 0;
my $reference_holder;
$choose_left ? \$left : $reference_holder = \6;
is $$reference_holder, 6, 'bare conditional branch receives the RHS reference value';

$choose_left = 0;
$choose_left ? \$left : \$right = \4;
is $right, 4, 'conditional target aliases an explicitly referenced false branch';

my $choose_nested = 1;
\($choose_nested ? $choose_left ? $left : $right : $right) = \5;
is $right, 5, 'nested conditional target binds the selected scalar slot';

my (@alias_list, $alias_scalar);
my ($first, $second, $third) = qw(first second third);
my $alias_count = ((\$alias_scalar, \(@alias_list)) = (\$first, \$second, \$third));
is $alias_count, 3, 'ref-alias list assignment returns its RHS count in scalar context';

my ($first_ref, $second_ref, $third_ref) = \($first, $second, $third);
((\$alias_scalar, \(@alias_list)) = ($first_ref, $second_ref, $third_ref)) = \(qw(replaced references only));
is $first_ref, \$first, 'outer assignment changes only the ref-alias expression temporaries';
is $second_ref, \$second, 'outer assignment preserves the second source reference';
is $third_ref, \$third, 'outer assignment preserves the aggregate source reference';

done_testing;
