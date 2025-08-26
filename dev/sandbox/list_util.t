#!/usr/bin/perl

use strict;
use warnings;
use Test::More;
use List::Util qw(
    reduce reductions any all none notall first
    min max minstr maxstr sum sum0 product
    shuffle sample uniq uniqint uniqnum uniqstr
    head tail pairs unpairs pairkeys pairvalues
    pairmap pairgrep pairfirst zip mesh
);

# Test reduce
subtest 'reduce' => sub {
    # Basic reduction
    my $sum = reduce { $a + $b } 1..10;
    is($sum, 55, 'reduce sum 1..10');

    my $product = reduce { $a * $b } 1..4;
    is($product, 24, 'reduce product 1..4');

    my $concat = reduce { $a . $b } 'a'..'d';
    is($concat, 'abcd', 'reduce string concatenation');

    # Edge cases
    is(reduce { $a + $b } (), undef, 'reduce empty list');
    is(reduce { $a + $b } (42), 42, 'reduce single element');

    # With identity value
    is(reduce { $a + $b } 0, (), 0, 'reduce with identity value');
};

# Test reductions
subtest 'reductions' => sub {
    my @results = reductions { $a + $b } 1..4;
    is_deeply(\@results, [1, 3, 6, 10], 'reductions cumulative sum');

    my @concat = reductions { "$a-$b" } 'a'..'d';
    is_deeply(\@concat, ['a', 'a-b', 'a-b-c', 'a-b-c-d'], 'reductions string concat');

    my @empty = reductions { $a + $b } ();
    is_deeply(\@empty, [], 'reductions empty list');
};

# Test any/all/none/notall
subtest 'boolean list functions' => sub {
    # any
    ok(any { $_ > 5 } 1..10, 'any - some elements > 5');
    ok(!any { $_ > 15 } 1..10, 'any - no elements > 15');
    ok(!any { $_ > 0 } (), 'any - empty list');

    # all
    ok(all { $_ > 0 } 1..10, 'all - all elements > 0');
    ok(!all { $_ > 5 } 1..10, 'all - not all elements > 5');
    ok(all { $_ > 0 } (), 'all - empty list');

    # none
    ok(none { $_ > 15 } 1..10, 'none - no elements > 15');
    ok(!none { $_ > 5 } 1..10, 'none - some elements > 5');
    ok(none { $_ > 0 } (), 'none - empty list');

    # notall
    ok(notall { $_ > 5 } 1..10, 'notall - not all elements > 5');
    ok(!notall { $_ > 0 } 1..10, 'notall - all elements > 0');
    ok(!notall { $_ > 0 } (), 'notall - empty list');
};

# Test first
subtest 'first' => sub {
    is(first { $_ > 5 } 1..10, 6, 'first element > 5');
    is(first { defined $_ } (undef, undef, 'hello'), 'hello', 'first defined');
    is(first { $_ > 15 } 1..10, undef, 'first - no match');
    is(first { $_ > 0 } (), undef, 'first - empty list');
};

# Test min/max
subtest 'min/max numerical' => sub {
    is(min(3, 1, 4, 1, 5), 1, 'min of numbers');
    is(max(3, 1, 4, 1, 5), 5, 'max of numbers');
    is(min(42), 42, 'min single element');
    is(max(42), 42, 'max single element');
    is(min(), undef, 'min empty list');
    is(max(), undef, 'max empty list');

    # Test with negative numbers
    is(min(-5, -1, -10), -10, 'min with negatives');
    is(max(-5, -1, -10), -1, 'max with negatives');
};

# Test minstr/maxstr
subtest 'min/max string' => sub {
    is(minstr('foo', 'bar', 'baz'), 'bar', 'minstr');
    is(maxstr('foo', 'bar', 'baz'), 'foo', 'maxstr');
    is(minstr('apple'), 'apple', 'minstr single element');
    is(maxstr('apple'), 'apple', 'maxstr single element');
    is(minstr(), undef, 'minstr empty list');
    is(maxstr(), undef, 'maxstr empty list');
};

# Test sum/sum0/product
subtest 'arithmetic functions' => sub {
    is(sum(1, 2, 3, 4), 10, 'sum of numbers');
    is(sum(1.5, 2.5), 4, 'sum with decimals');
    is(sum(), undef, 'sum empty list');

    is(sum0(1, 2, 3, 4), 10, 'sum0 of numbers');
    is(sum0(), 0, 'sum0 empty list');

    is(product(2, 3, 4), 24, 'product of numbers');
    is(product(2.5, 4), 10, 'product with decimals');
    is(product(), 1, 'product empty list');
};

# Test shuffle
subtest 'shuffle' => sub {
    my @orig = 1..10;
    my @shuffled = shuffle(@orig);

    is(scalar(@shuffled), scalar(@orig), 'shuffle preserves count');

    # Check all elements are present
    my @sorted_orig = sort @orig;
    my @sorted_shuffled = sort @shuffled;
    is_deeply(\@sorted_shuffled, \@sorted_orig, 'shuffle preserves elements');

    # Empty list
    my @empty = shuffle();
    is_deeply(\@empty, [], 'shuffle empty list');
};

# Test sample
subtest 'sample' => sub {
    my @sample = sample(3, 1..10);
    is(scalar(@sample), 3, 'sample returns correct count');

    # Sample more than available
    my @all = sample(15, 1..5);
    is(scalar(@all), 5, 'sample more than available');

    # Sample zero
    my @none = sample(0, 1..10);
    is_deeply(\@none, [], 'sample zero elements');

    # Empty source
    my @from_empty = sample(5, ());
    is_deeply(\@from_empty, [], 'sample from empty list');
};

# Test uniq variants
subtest 'unique functions' => sub {
    # uniq/uniqstr
    my @uniq_str = uniq('foo', 'bar', 'foo', 'baz', 'bar');
    is_deeply([sort @uniq_str], [sort ('foo', 'bar', 'baz')], 'uniq strings');

    # uniqnum
    my @uniq_num = uniqnum(1, 2.0, 1, 3, 2);
    is_deeply([sort { $a <=> $b } @uniq_num], [1, 2, 3], 'uniqnum');

    # uniqint
    my @uniq_int = uniqint(1, 2, 1, 3, 2);
    is_deeply([sort { $a <=> $b } @uniq_int], [1, 2, 3], 'uniqint');

    # With undef
    my @with_undef = uniq(undef, 'foo', undef, 'bar');
    is(scalar(@with_undef), 3, 'uniq with undef');

    # Empty list
    my @empty = uniq();
    is_deeply(\@empty, [], 'uniq empty list');

    # Scalar context
    my $count = uniq('a', 'b', 'a', 'c');
    is($count, 3, 'uniq in scalar context');
};

# Test head/tail
subtest 'head and tail' => sub {
    my @list = 1..10;

    # head
    my @head3 = head(3, @list);
    is_deeply(\@head3, [1, 2, 3], 'head 3 elements');

    my @head_negative = head(-2, @list);
    is_deeply(\@head_negative, [1..8], 'head negative (all but last 2)');

    my @head_more = head(15, @list);
    is_deeply(\@head_more, \@list, 'head more than available');

    # tail
    my @tail3 = tail(3, @list);
    is_deeply(\@tail3, [8, 9, 10], 'tail 3 elements');

    my @tail_negative = tail(-2, @list);
    is_deeply(\@tail_negative, [3..10], 'tail negative (all but first 2)');

    my @tail_more = tail(15, @list);
    is_deeply(\@tail_more, \@list, 'tail more than available');

    # Empty list
    my @empty_head = head(3, ());
    is_deeply(\@empty_head, [], 'head from empty list');

    my @empty_tail = tail(3, ());
    is_deeply(\@empty_tail, [], 'tail from empty list');
};

# Test pair functions
subtest 'pair functions' => sub {
    my @kvlist = ('a', 1, 'b', 2, 'c', 3, 'd');

    # pairs
    my @pairs = pairs(@kvlist);
    is(scalar(@pairs), 4, 'pairs count');
    is_deeply($pairs[0], ['a', 1], 'first pair');
    is_deeply($pairs[1], ['b', 2], 'second pair');
    is_deeply($pairs[3], ['d', undef], 'odd pair gets undef');

    # unpairs
    my @unpaired = unpairs(@pairs);
    is_deeply(\@unpaired, ['a', 1, 'b', 2, 'c', 3, 'd', undef], 'unpairs');

    # pairkeys
    my @keys = pairkeys(@kvlist);
    is_deeply(\@keys, ['a', 'b', 'c', 'd'], 'pairkeys');

    # pairvalues
    my @values = pairvalues(@kvlist);
    is_deeply(\@values, [1, 2, 3], 'pairvalues');

    # pairmap
    my @mapped = pairmap { "$a=$b" } @kvlist;
    is_deeply(\@mapped, ['a=1', 'b=2', 'c=3', 'd='], 'pairmap');

    # pairgrep
    my @filtered = pairgrep { $b > 1 } @kvlist;
    is_deeply(\@filtered, ['b', 2, 'c', 3], 'pairgrep');

    # pairfirst
    my @first = pairfirst { $b > 1 } @kvlist;
    is_deeply(\@first, ['b', 2], 'pairfirst list context');

    my $found = pairfirst { $b > 1 } @kvlist;
    ok($found, 'pairfirst scalar context - found');

    my $not_found = pairfirst { $b > 10 } @kvlist;
    ok(!$not_found, 'pairfirst scalar context - not found');
};

# Test zip and mesh
subtest 'zip and mesh' => sub {
    my @arr1 = (1, 2, 3);
    my @arr2 = ('a', 'b', 'c', 'd');
    my @arr3 = (10, 20);

    # zip
    my @zipped = zip(\@arr1, \@arr2, \@arr3);
    is(scalar(@zipped), 4, 'zip result count');
    is_deeply($zipped[0], [1, 'a', 10], 'zip first tuple');
    is_deeply($zipped[1], [2, 'b', 20], 'zip second tuple');
    is_deeply($zipped[2], [3, 'c', undef], 'zip third tuple with undef');
    is_deeply($zipped[3], [undef, 'd', undef], 'zip fourth tuple');

    # mesh
    my @meshed = mesh(\@arr1, \@arr2, \@arr3);
    is_deeply(\@meshed, [1, 'a', 10, 2, 'b', 20, 3, 'c', undef, undef, 'd', undef], 'mesh result');

    # Empty arrays
    my @empty_zip = zip();
    is_deeply(\@empty_zip, [], 'zip empty arrays');

    my @empty_mesh = mesh();
    is_deeply(\@empty_mesh, [], 'mesh empty arrays');
};

# Test edge cases and error conditions
subtest 'edge cases' => sub {
    # Functions with code blocks should handle empty lists
    is(any { $_ > 0 } (), '', 'any with empty list');
    is(all { $_ > 0 } (), 1, 'all with empty list');
    is(first { $_ > 0 } (), undef, 'first with empty list');

    # Numeric functions should handle mixed types
    my $mixed_sum = sum('5', 3, '2.5');
    is($mixed_sum, 10.5, 'sum handles mixed string/numeric');

    # String functions should handle numbers
    is(minstr(10, 2, 3), '10', 'minstr with numbers (lexical sort)');

    # Test context sensitivity where applicable
    my @list_context = head(3, 1..10);
    my $scalar_context = head(3, 1..10);
    is(ref(\@list_context), 'ARRAY', 'head returns array in list context');
};

done_testing();