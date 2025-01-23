#!/usr/bin/perl
use strict;
use warnings;

# Define the hierarchy of warning categories
my %warning_hierarchy = (
    'all' => [
        'closure',
        'deprecated' => [
            'deprecated::apostrophe_as_package_separator',
            'deprecated::delimiter_will_be_paired',
            'deprecated::dot_in_inc',
            'deprecated::goto_construct',
            'deprecated::missing_import_called_with_args',
            'deprecated::smartmatch',
            'deprecated::subsequent_use_version',
            'deprecated::unicode_property_name',
            'deprecated::version_downgrade',
        ],
        'exiting',
        'experimental' => [
            'experimental::args_array_with_signatures',
            'experimental::builtin',
            'experimental::class',
            'experimental::declared_refs',
            'experimental::defer',
            'experimental::extra_paired_delimiters',
            'experimental::private_use',
            'experimental::re_strict',
            'experimental::refaliasing',
            'experimental::regex_sets',
            'experimental::try',
            'experimental::uniprop_wildcards',
            'experimental::vlb',
        ],
        'glob',
        'imprecision',
        'io' => [
            'io::closed',
            'io::exec',
            'io::layer',
            'io::newline',
            'io::pipe',
            'io::syscalls',
            'io::unopened',
        ],
        'locale',
        'misc',
        'missing',
        'numeric',
        'once',
        'overflow',
        'pack',
        'portable',
        'recursion',
        'redefine',
        'redundant',
        'regexp',
        'scalar',
        'severe' => [
            'severe::debugging',
            'severe::inplace',
            'severe::internal',
            'severe::malloc',
        ],
        'shadow',
        'signal',
        'substr',
        'syntax' => [
            'syntax::ambiguous',
            'syntax::bareword',
            'syntax::digit',
            'syntax::illegalproto',
            'syntax::parenthesis',
            'syntax::precedence',
            'syntax::printf',
            'syntax::prototype',
            'syntax::qw',
            'syntax::reserved',
            'syntax::semicolon',
        ],
        'taint',
        'threads',
        'uninitialized',
        'unpack',
        'untie',
        'utf8' => [
            'utf8::non_unicode',
            'utf8::nonchar',
            'utf8::surrogate',
        ],
        'void',
    ],
);

# Function to report enabled warnings
sub report_enabled_warnings {
    my ($categories, $prefix) = @_;
    $prefix //= '';

    for (my $i = 0; $i < @$categories; $i++) {
        my $category = $categories->[$i];
        if (ref $category eq 'ARRAY') {
            # Skip arrays, they are subcategories
            next;
        }

        eval {
            if (warnings::enabled($category)) {
                print "${prefix}- $category\n";
            }
            1;
        } or do {
            # print $@;
        };

        if (ref $categories->[$i + 1] eq 'ARRAY') {
            report_enabled_warnings($categories->[$i + 1], "$prefix  ");
        }
    }
}

# Call the function to report enabled warnings
print "Enabled Warnings:\n";
report_enabled_warnings($warning_hierarchy{'all'});

