package feature;
our $VERSION = '1.99';

# Load the Java implementation first
BEGIN { XSLoader::load('Feature'); }

# Feature names mapped to internal feature flags
# This hash is used by experimental.pm to check if a feature exists
our %feature = (
    fc                              => 'feature_fc',
    isa                             => 'feature_isa',
    say                             => 'feature_say',
    try                             => 'feature_try',
    class                           => 'feature_class',
    defer                           => 'feature_defer',
    state                           => 'feature_state',
    switch                          => 'feature_switch',
    bitwise                         => 'feature_bitwise',
    indirect                        => 'feature_indirect',
    evalbytes                       => 'feature_evalbytes',
    signatures                      => 'feature_signatures',
    smartmatch                      => 'feature_smartmatch',
    current_sub                     => 'feature___SUB__',
    keyword_all                     => 'feature_keyword_all',
    keyword_any                     => 'feature_keyword_any',
    module_true                     => 'feature_module_true',
    refaliasing                     => 'feature_refaliasing',
    postderef_qq                    => 'feature_postderef_qq',
    unicode_eval                    => 'feature_unieval',
    declared_refs                   => 'feature_myref',
    unicode_strings                 => 'feature_unicode',
    multidimensional                => 'feature_multidimensional',
    bareword_filehandles            => 'feature_bareword_filehandles',
    extra_paired_delimiters         => 'feature_more_delims',
    apostrophe_as_package_separator => 'feature_apos_as_name_sep',
);

# Feature bundles for different Perl versions
our %feature_bundle = (
    "5.10"    => [qw(apostrophe_as_package_separator bareword_filehandles indirect multidimensional say smartmatch state switch)],
    "5.11"    => [qw(apostrophe_as_package_separator bareword_filehandles indirect multidimensional say smartmatch state switch unicode_strings)],
    "5.15"    => [qw(apostrophe_as_package_separator bareword_filehandles current_sub evalbytes fc indirect multidimensional say smartmatch state switch unicode_eval unicode_strings)],
    "5.23"    => [qw(apostrophe_as_package_separator bareword_filehandles current_sub evalbytes fc indirect multidimensional postderef_qq say smartmatch state switch unicode_eval unicode_strings)],
    "5.27"    => [qw(apostrophe_as_package_separator bareword_filehandles bitwise current_sub evalbytes fc indirect multidimensional postderef_qq say smartmatch state switch unicode_eval unicode_strings)],
    "5.35"    => [qw(apostrophe_as_package_separator bareword_filehandles bitwise current_sub evalbytes fc isa postderef_qq say signatures smartmatch state unicode_eval unicode_strings)],
    "5.37"    => [qw(apostrophe_as_package_separator bitwise current_sub evalbytes fc isa module_true postderef_qq say signatures smartmatch state unicode_eval unicode_strings)],
    "5.39"    => [qw(apostrophe_as_package_separator bitwise current_sub evalbytes fc isa module_true postderef_qq say signatures smartmatch state try unicode_eval unicode_strings)],
    "5.41"    => [qw(bitwise current_sub evalbytes fc isa module_true postderef_qq say signatures state try unicode_eval unicode_strings)],
    "all"     => [qw(apostrophe_as_package_separator bareword_filehandles bitwise class current_sub declared_refs defer evalbytes extra_paired_delimiters fc indirect isa keyword_all keyword_any module_true multidimensional postderef_qq refaliasing say signatures smartmatch state switch try unicode_eval unicode_strings)],
    "default" => [qw(apostrophe_as_package_separator bareword_filehandles indirect multidimensional smartmatch)],
);

# Copy bundles for intermediate versions
$feature_bundle{"5.12"} = $feature_bundle{"5.11"};
$feature_bundle{"5.13"} = $feature_bundle{"5.11"};
$feature_bundle{"5.14"} = $feature_bundle{"5.11"};
$feature_bundle{"5.16"} = $feature_bundle{"5.15"};
$feature_bundle{"5.17"} = $feature_bundle{"5.15"};
$feature_bundle{"5.18"} = $feature_bundle{"5.15"};
$feature_bundle{"5.19"} = $feature_bundle{"5.15"};
$feature_bundle{"5.20"} = $feature_bundle{"5.15"};
$feature_bundle{"5.21"} = $feature_bundle{"5.15"};
$feature_bundle{"5.22"} = $feature_bundle{"5.15"};
$feature_bundle{"5.24"} = $feature_bundle{"5.23"};
$feature_bundle{"5.25"} = $feature_bundle{"5.23"};
$feature_bundle{"5.26"} = $feature_bundle{"5.23"};
$feature_bundle{"5.28"} = $feature_bundle{"5.27"};
$feature_bundle{"5.29"} = $feature_bundle{"5.27"};
$feature_bundle{"5.30"} = $feature_bundle{"5.27"};
$feature_bundle{"5.31"} = $feature_bundle{"5.27"};
$feature_bundle{"5.32"} = $feature_bundle{"5.27"};
$feature_bundle{"5.33"} = $feature_bundle{"5.27"};
$feature_bundle{"5.34"} = $feature_bundle{"5.27"};
$feature_bundle{"5.36"} = $feature_bundle{"5.35"};
$feature_bundle{"5.38"} = $feature_bundle{"5.37"};
$feature_bundle{"5.40"} = $feature_bundle{"5.39"};
$feature_bundle{"5.42"} = $feature_bundle{"5.41"};
$feature_bundle{"5.43"} = $feature_bundle{"5.41"};
$feature_bundle{"5.44"} = $feature_bundle{"5.41"};
$feature_bundle{"5.9.5"} = $feature_bundle{"5.10"};

1;
