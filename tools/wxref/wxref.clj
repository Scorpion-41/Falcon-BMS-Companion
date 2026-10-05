;; WeatherGen's own model, run on the JVM, as the reference the Kotlin port is checked against.
;;
;; Usage (Clojure 1.11 and its two spec jars on the classpath, plus the src folder of VMT 0.63, Tyrant's Virtual
;; Mission Tools, which carries WeatherGen):
;;   java -cp "clojure-1.11.1.jar;spec.alpha-0.3.218.jar;core.specs.alpha-0.2.62.jar;<vmt>/src" \
;;        clojure.main tools/wxref/wxref.clj reference.tsv
;; then  --wxgentest reference.tsv report.txt  asks the port for the same scenarios and demands the same answers.
;;
;; Every number handed to the model is a double. VMT runs as ClojureScript, where every number is a double; on the
;; JVM a whole number stays a long, and (/ 1003 10) is then the exact ratio 1003/10 rather than the double 100.3 --
;; which would make this reference more exact than the program it stands for. The scenarios here must match
;; `WxGenTest.scenarios` in desktop/.../bridge/WxGenTest.kt field for field.
(require '[weathergen.model :as model]
         '[weathergen.time :as time]
         '[clojure.string :as str])

(def aloft
  {3000  {:speed {:from 2.0 :to 3.0} :bias 0.1}
   6000  {:speed {:from 4.0 :to 6.0} :bias 0.2}
   9000  {:speed {:from 7.0 :to 9.0} :bias 0.3}
   12000 {:speed {:from 8.0 :to 12.0} :bias 0.4}
   18000 {:speed {:from 11.0 :to 13.0} :bias 0.5}
   24000 {:speed {:from 13.0 :to 17.0} :bias 0.6}
   30000 {:speed {:from 16.0 :to 18.0} :bias 0.7}
   40000 {:speed {:from 18.0 :to 22.0} :bias 0.8}
   50000 {:speed {:from 20.0 :to 25.0} :bias 0.9}})

;; ui.cljs default-weather-params, as doubles
(def defaults
  {:temp-uniformity 0.7
   :pressure        {:min 28.0 :max 31.0 :variance 1.2 :speed 100.0}
   :cell-count      [59 59]
   :feature-size    10.0
   :categories      {:sunny     {:weight 50.0 :wind {:min 5.0 :mean 10.0 :max 30.0} :temp {:min 20.0 :mean 22.0 :max 24.0}
                                 :visibility {:from 24.0 :to 48.0}}
                     :fair      {:weight 50.0 :wind {:min 0.0 :mean 7.0 :max 20.0} :temp {:min 18.0 :mean 21.0 :max 23.0}
                                 :visibility {:from 16.0 :to 48.0}
                                 :low-clouds {:base {:from 3000.0 :to 10000.0} :size {:from 0.0 :to 5.0}
                                              :coverage {:from :few :to :broken} :towering 0.4}}
                     :poor      {:weight 50.0 :wind {:min 10.0 :mean 15.0 :max 30.0} :temp {:min 15.0 :mean 18.0 :max 21.0}
                                 :visibility {:from 8.0 :to 16.0}
                                 :low-clouds {:base {:from 0.0 :to 10000.0} :size {:from 0.0 :to 5.0}
                                              :coverage {:from :scattered :to :overcast} :towering 0.4}}
                     :inclement {:weight 50.0 :wind {:min 15.0 :mean 25.0 :max 60.0} :temp {:min 12.0 :mean 14.0 :max 16.0}
                                 :visibility {:from 3.0 :to 11.0}
                                 :low-clouds {:base {:from 0.0 :to 10000.0} :size {:from 0.0 :to 5.0}
                                              :coverage {:from :scattered :to :overcast} :towering 0.35}}}
   :winds-aloft     aloft
   :turbulence      {:size 1.0 :power 250.0}
   :origin          [1000.0 1000.0]
   :evolution       3600.0
   :time            {:offset 1234.0 :current {:day 1 :hour 5 :minute 0} :max nil}
   :wind-uniformity 0.7
   :crossfade       0.1
   :prevailing-wind {:heading 325.0}
   :seed            1234.0
   :weather-overrides []})

(def movement {:step 60.0 :direction {:heading 135.0 :speed 20.0}})

(defn at [p day hour minute] (assoc-in p [:time :current] {:day day :hour hour :minute minute}))

(def storm
  {:location {:x 20.0 :y 30.0} :radius 12.0 :falloff 4.0 :strength 0.8 :animate? true
   :begin {:day 1 :hour 5 :minute 0} :peak {:day 1 :hour 7 :minute 0}
   :taper {:day 1 :hour 10 :minute 0} :end {:day 1 :hour 12 :minute 0}
   :type :inclement :temperature 5.0 :visibility 2.0 :cloud-cover :overcast :cloud-base 800.0 :cloud-size 1.0
   :towering? true :wind-dir 270.0 :wind-speed 40.0 :wind-alts [0 3000 6000] :exclude-from-forecast? true})

(def clear
  {:location {:x 45.0 :y 10.0} :radius 9.0 :falloff 2.0 :strength 1.0 :animate? false
   :begin {:day 1 :hour 5 :minute 0} :peak {:day 1 :hour 6 :minute 0}
   :taper {:day 1 :hour 8 :minute 0} :end {:day 1 :hour 9 :minute 0}
   :type :fair :towering? false :wind-dir 10.0 :wind-alts [0 3000 6000 9000 12000 18000 24000 30000 40000 50000]})

(def tuned
  (-> defaults
      (assoc :seed 777.0 :feature-size 7.0 :crossfade 0.2 :wind-uniformity 0.5 :evolution 1800.0
             :origin [250.5 -40.25] :turbulence {:size 2.0 :power 120.0} :prevailing-wind {:heading 10.0})
      (assoc-in [:time :offset] -300.0)
      (assoc-in [:pressure :variance] 0.8)
      (assoc-in [:categories :sunny :weight] 20.0)
      (assoc-in [:categories :poor :weight] 70.0)
      (assoc-in [:categories :fair :low-clouds :towering] 0.6)
      (assoc :weather-overrides [storm clear])))

(def scenarios
  [["s1234-d1-0500" (at (assoc defaults :seed 1234.0) 1 5 0)]
   ["s1234-d1-1330" (at (assoc defaults :seed 1234.0) 1 13 30)]
   ["s1234-d3-2210" (at (assoc defaults :seed 1234.0) 3 22 10)]
   ["s42-d1-0500"   (at (assoc defaults :seed 42.0) 1 5 0)]
   ["s42-d1-1330"   (at (assoc defaults :seed 42.0) 1 13 30)]
   ["s42-d3-2210"   (at (assoc defaults :seed 42.0) 3 22 10)]
   ["s4711-d1-0500" (at (assoc defaults :seed 4711.0) 1 5 0)]
   ["s4711-d1-1330" (at (assoc defaults :seed 4711.0) 1 13 30)]
   ["s4711-d3-2210" (at (assoc defaults :seed 4711.0) 3 22 10)]
   ["tuned-d1-0600" (at tuned 1 6 0)]
   ["tuned-d1-0800" (at tuned 1 8 0)]
   ["tuned-d1-1100" (at tuned 1 11 0)]
   ;; VMT's own step function: the pattern both moves and evolves
   ["tuned-step3"   (model/step (at tuned 1 6 0) movement 3)]
   ["s42-step5"     (model/step (at (assoc defaults :seed 42.0) 1 5 0) movement 5)]])

(defn fmt [v]
  (cond (keyword? v) (name v)
        (boolean? v) (if v "1" "0")
        (ratio? v) (str (double v))
        :else (str v)))

(defn row [scen x y w]
  (str/join "\t"
            (concat [scen x y (fmt (:value w)) (fmt (:type w)) (fmt (:pressure w)) (fmt (:temperature w))]
                    (map #(fmt (get-in w [:wind % :speed])) model/wind-alts)
                    (map #(fmt (get-in w [:wind % :heading])) model/wind-alts)
                    [(fmt (get-in w [:low-clouds :coverage])) (fmt (get-in w [:low-clouds :towering?]))
                     (fmt (get-in w [:low-clouds :base])) (fmt (get-in w [:low-clouds :size]))
                     (fmt (:visibility w))])))

(let [out (first *command-line-args*)]
  (with-open [w (clojure.java.io/writer out)]
    (.write w (str "# WeatherGen (VMT 0.63) model.cljc on the JVM; columns: scen x y value type pressure temp "
                   "speed@0..50000 heading@0..50000 coverage towering base size visibility\n"))
    (doseq [[scen p] scenarios
            :let [cells (for [x (range 59) y (range 59)] [x y])
                  grid (model/weather-grid (assoc p :cells cells))]
            [[x y] wx] grid]
      (.write w (row scen x y wx))
      (.write w "\n"))
    ;; the forecast for two cells: "fc-<name>@<minutes>", then the same columns
    (doseq [[scen p cell] [["fc-tuned" (at tuned 1 6 0) [20 30]] ["fc-s42" (at (assoc defaults :seed 42.0) 1 5 0) [10 40]]]
            [t wx] (model/forecast cell p movement 60.0 6)]
      (.write w (row (str scen "@" (time/campaign-time->minutes t)) (first cell) (second cell) wx))
      (.write w "\n"))))
